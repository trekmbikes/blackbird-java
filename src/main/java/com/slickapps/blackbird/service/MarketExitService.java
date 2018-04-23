package com.slickapps.blackbird.service;

import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.FILLED;
import static com.slickapps.blackbird.model.orderCompletion.OrderRollbackType.REMAINING;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.lang.Boolean.logicalXor;
import static java.math.MathContext.DECIMAL64;
import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.apache.commons.lang3.StringUtils.rightPad;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.BlackbirdEventListener;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.OrderPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;
import com.slickapps.blackbird.model.SpreadBounds;
import com.slickapps.blackbird.model.TrailingDetails;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletion;
import com.slickapps.blackbird.processes.OrderCompletionPoller;
import com.slickapps.blackbird.util.FormatUtil;
import com.slickapps.blackbird.util.exception.OrderPlacementException;
import com.slickapps.blackbird.util.exception.PairsInMarketUpdatedNotification;

/**
 * @author barrycon
 *
 */
public class MarketExitService extends AbstractMarketService {
	private static final Logger log = LoggerFactory.getLogger(MarketExitService.class);

	protected Parameters params;
	protected EventListenerProvider eventListenerProvider;
	protected QuoteService quoteService;
	protected SpreadMonitor spreadMonitor;
	protected TrailingStopFilter trailingStopFilter;
	protected MarketPairsProvider marketPairsProvider;
	protected StringPaddingService stringPaddingService = new StringPaddingService();
	protected AtomicInteger resultCount = new AtomicInteger();

	public MarketExitService(Parameters params, MarketPairsProvider marketPairsProvider,
			EventListenerProvider eventListenerProvider, QuoteService quoteService, SpreadMonitor spreadMonitor) {
		this.params = params;
		this.marketPairsProvider = marketPairsProvider;
		this.eventListenerProvider = eventListenerProvider;
		this.quoteService = quoteService;
		this.spreadMonitor = spreadMonitor;
		trailingStopFilter = new TrailingStopFilter(params, false);
	}

	public ExchangePairInMarket prepareNextPairReadyToExit(ExchangePairsInMarket exchangePairsInMarket)
			throws IOException, InterruptedException, ExecutionException, PairsInMarketUpdatedNotification {
		if (exchangePairsInMarket.getNumPairsInMarket() == 0)
			return null;

		/*
		 * Let's make a shallow copy so we can iterate over the list without blocking
		 * other synchronized items. TODO The forEach() method would be better design
		 * here.
		 */
		ExchangePairsInMarket epimCopy = exchangePairsInMarket.getPairsInMarketCopy(false);
		SortedSet<ExchangePairInMarket> pairsInMarket = epimCopy.getPairsInMarket();

		for (ExchangePairInMarket epim : pairsInMarket) {
			if (!epim.getLongExchange().isEnabled() || !epim.getShortExchange().isEnabled()
					|| epim.getLongExchange().isDisabledTemporarilyOrNeedsWalletPopulation()
					|| epim.getShortExchange().isDisabledTemporarilyOrNeedsWalletPopulation())
				continue;

			/*
			 * If this epim isn't even finished entering the market, we don't want to
			 * process it for exiting yet...
			 */
			if (!epim.isBothEntryOrdersFilled())
				continue;

			/*
			 * If either exit order is placed, we're already on the way out of the market so
			 * ignore this pair. If both exit orders are placed, it means we're just waiting
			 * on those orders to be filled before we fully exit. If only one order is
			 * placed but not the other, that means we had a connection failure with one of
			 * the exchanges previously. We need to reattempt the exit order. But we only
			 * want our follow up attempts to place closing orders to come after new pairs
			 * ready to exit are processed. So we'll make two passes - the first will look
			 * for new pairs ready for exit, and only if we don't find one of these will we
			 * retry failed orders.
			 */
			if (epim.isEitherExitOrderPlaced())
				continue;

			Optional<Quote> latestLongQuote = quoteService.getLatestQuote(epim.toLongExchangeAndCurrencyPair());
			Optional<Quote> latestShortQuote = quoteService.getLatestQuote(epim.toShortExchangeAndCurrencyPair());

			/*
			 * We don't yet have a quote from this exchange & currency pair; skip it until
			 * we do
			 */
			if (!latestLongQuote.isPresent() || !latestShortQuote.isPresent()) {
				log.debug("Short or long quote was null; continuing...");
				continue;
			}

			QuotePair quotePair = new QuotePair(latestLongQuote.get(), latestShortQuote.get());
			if (!quoteService.quotesComparable(quotePair))
				continue;

			for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
				l.quotePairEvaluation(quotePair, false);

			boolean shouldExit = evaluate(quotePair, epim);
			if (!shouldExit)
				continue;

			try {
				placeLongAndShortOrders(epim);
				return epim;
			} catch (OrderPlacementException e) {
				/*
				 * one or both orders couldn't be placed; we'll retry later down below if either
				 * order was placed successfully
				 */
			}
		}

		/*
		 * Since we don't have any new pairs ready to exit (above), let's reprocess any
		 * that previously were ready to exit but we had a problem placing one or both
		 * of their exit orders.
		 */
		for (ExchangePairInMarket epim : pairsInMarket) {
			if (epim.getLongExchange().isDisabledTemporarilyOrNeedsWalletPopulation()
					|| epim.getShortExchange().isDisabledTemporarilyOrNeedsWalletPopulation())
				continue;

			/*
			 * If orders were successfully placed this time, return the epim and let the
			 * system start ExitOrderCompletionPollers.
			 */
			if (epim.isEitherExitOrderPlaced()) {
				try {
					placeLongAndShortOrders(epim);
					return epim;
				} catch (OrderPlacementException e) {
					; // continue to the next epim
				}
			}
		}

		return null;
	}

	/**
	 * Checks for exit opportunity between two exchanges and returns True if an
	 * opportunity is found.
	 * 
	 * @throws IOException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	private boolean evaluate(QuotePair quotePair, ExchangePairInMarket epim) throws IOException, InterruptedException {
		BigDecimal currentSpread = quotePair.getSpreadIfExiting();
		BigDecimal targetExitSpread = epim.getExitTarget();

		/* Accommodate manual data updates outside the app */
		if (epim.isBothExitOrdersFilled())
			return true;

		/*
		 * If either price is null, bail out since one of the two quotes might not have
		 * come in yet
		 */
		if (currentSpread == null || currentSpread.signum() == 0)
			return false;

		Quote longQuote = quotePair.getLongQuote();
		Quote shortQuote = quotePair.getShortQuote();

		BlackbirdExchange longExchange = longQuote.getExchange();
		BlackbirdExchange shortExchange = shortQuote.getExchange();

		CurrencyPair longCurrencyPair = longQuote.getCurrencyPair();
		CurrencyPair shortCurrencyPair = shortQuote.getCurrencyPair();

		ExchangePairAndCurrencyPair ecp = new ExchangePairAndCurrencyPair(longQuote, shortQuote);

		/*
		 * Now that I'm trying to exit the market, I want to sell back my long asset (so
		 * I'm interested in what people are bidding) and I want to repurchase my short
		 * asset to close my short position (so I'm interested in what people are
		 * asking)
		 */
		BigDecimal marketPriceLong = longQuote.getBid();
		BigDecimal marketPriceShort = shortQuote.getAsk();

		TrailingDetails trailing = trailingStopFilter.getOrCreateTrailingDetails(ecp);

		if (log.isDebugEnabled()) {
			NumberFormat pctF = FormatUtil.getPercentFormatter();

			boolean sameBase = longCurrencyPair.base.equals(shortCurrencyPair.base);
			String exchangeStr = sameBase ? longExchange + "/" + shortExchange + " " + longCurrencyPair.base
					: longExchange + " " + longCurrencyPair.base + "/" + shortExchange + " " + shortCurrencyPair.base;
			String currenciesStr = formatCurrency(longCurrencyPair.counter, marketPriceLong) + " vs "
					+ formatCurrency(shortCurrencyPair.counter, marketPriceShort);
			Optional<SpreadBounds> spreadBounds = spreadMonitor.getSpreadBounds(ecp);
			log.debug("<-  {}{} {} [target < {}, min {}, max {}]",
					rightPad(exchangeStr + ": ", stringPaddingService.getMaxLen("exchangePrefix", exchangeStr) + 2),
					rightPad(currenciesStr, stringPaddingService.getMaxLen("currenciesStr", currenciesStr)),
					leftPad(pctF.format(currentSpread), 6), leftPad(pctF.format(epim.getExitTarget()), 6),
					leftPad(spreadBounds.isPresent() ? pctF.format(spreadBounds.get().getGlobalMin()) : "-", 6),
					leftPad(spreadBounds.isPresent() ? pctF.format(spreadBounds.get().getGlobalMax()) : "-", 6));

			/*
			 * The short-term volatility is computed and displayed. No other action with it
			 * for the moment.
			 */
			// if (params.useVolatility) {
			// List<BigDecimal> volatility = res.volatility.get(longName, shortName);
			// if (volatility.size() >= params.volatilityPeriod) {
			// double stdev = compute_sd(volatility);
			// log.info(" volat. {}", pct2.format(stdev));
			// } else {
			// log.info(" volat. n/a {} < {}", volatility.size(), params.volatilityPeriod);
			// }
			// }

			if (trailing.hasTrailingSpread()) {
				/*
				 * Prints the trailing spread.
				 */
				log.debug("\t\ttrailing {} - {}/{}", pctF.format(trailing.getTrailingStop()),
						trailing.getTrailingStopApprovalCount(), params.trailingRequiredConfirmationPeriods);
			}
		}

		// TODO exit the market because we exceeded our maxLengthSeconds param

		// if (Duration.between(res.entryTime, period).getSeconds() >=
		// params.maxLengthSeconds) {
		// res.priceLongOut = priceLong;
		// res.priceShortOut = priceShort;
		// // exit the market here
		// return true;
		// }

		if (!trailingStopFilter.evaluate(ecp, currentSpread, targetExitSpread))
			return false;

		// Checks the volumes and computes the limit prices that will be sent to the
		// exchanges
		if (params.verbose)
			log.info("Exit opportunity found, checking limit prices...");

		CompletableFuture<BigDecimal> limPriceLongFuture = longExchange.queryLimitPrice(longCurrencyPair,
				epim.getEntryVolumeLong(), BID);
		CompletableFuture<BigDecimal> limPriceShortFuture = shortExchange.queryLimitPrice(shortCurrencyPair,
				epim.getEntryVolumeShort(), ASK);

		BigDecimal limPriceLong = null, limPriceShort = null;
		try {
			limPriceLong = limPriceLongFuture.get();
			limPriceShort = limPriceShortFuture.get();
		} catch (ExecutionException e) {
			log.error("Error retrieving limit prices from the exchange", e);
			return false;
		}

		if (limPriceLong.signum() == 0 || limPriceShort.signum() == 0) {
			log.warn("Opportunity found but error with the order books (limit price is null). Trade canceled");
			log.warn(" Long limit price: {}", formatCurrency(longCurrencyPair.counter, limPriceLong));
			log.warn(" Short limit price: {}", formatCurrency(shortCurrencyPair.counter, limPriceShort));
			trailing.reset();
			return false;
		}

		/*
		 * We want to make sure the price needed to satisfy our exit orders (the limit
		 * price) isn't too far away from the current market price; otherwise, it means
		 * not enough people are trading at a fast enough rate (meaning low liquidity)
		 * and we risk paying way too much or way too little to satisfy our orders,
		 * which would jeopardize our calculations.
		 */
		if (marketPriceLong.subtract(limPriceLong).compareTo(params.maxLimitPriceDifference) == 1
				|| limPriceShort.subtract(marketPriceShort).compareTo(params.maxLimitPriceDifference) == 1) {
			log.warn("Opportunity found but not enough liquidity. Trade canceled");
			log.warn(" Target long price: {}, Actual long price needed to satisfy order: {}",
					formatCurrency(longCurrencyPair.counter, marketPriceLong), limPriceLong);
			log.warn(" Target short price: {}, Actual short price needed to satisfy order: {}",
					formatCurrency(shortCurrencyPair.counter, marketPriceShort), limPriceShort);
			trailing.reset();
			return false;
		}

		epim.setExitVolumeLong(epim.getEntryVolumeLong());
		epim.setExitPriceLong(limPriceLong);
		epim.setExitVolumeShort(epim.getEntryVolumeShort());
		epim.setExitPriceShort(limPriceShort);

		/* reset trailing data since we just approved this pair for exit */
		trailing.reset();

		return true;
	}

	/**
	 * @param epim
	 *            The pair whose long and short orders we will be placing. We only
	 *            place orders that haven't already been placed. If both orders were
	 *            successfully placed in this method, we return normally; if either
	 *            order had a problem being placed, we throw a
	 *            PairsInMarketUpdatedNotification.
	 * @throws PairsInMarketUpdatedNotification
	 */
	private void placeLongAndShortOrders(ExchangePairInMarket epim) throws OrderPlacementException {
		BlackbirdExchange longExchange = epim.getLongExchange();
		BlackbirdExchange shortExchange = epim.getShortExchange();

		/*
		 * If one is placed but not the other, it's possible the prices changed
		 * substantially since we placed (& potentially filled) the successful order. So
		 * we need to get out of the market quickly - we'll use the market price on our
		 * remaining exchange.
		 */
		boolean longOrderPlaced = epim.isExitLongOrderPlaced();
		boolean shortOrderPlaced = epim.isExitShortOrderPlaced();
		boolean useMarketOrder = logicalXor(longOrderPlaced, shortOrderPlaced);

		CompletableFuture<String> longOrderIdFuture = null;
		if (!longOrderPlaced) {
			log.info("Placing long exit order...");

			BigDecimal quantityLong = longExchange.roundQuantityToStepSizeIfNecessary(true,
					epim.getExitVolumeLong().abs(), epim.getLongCurrencyPair());
			BigDecimal priceLong = longExchange.roundQuantityToStepSizeIfNecessary(true, epim.getExitPriceLong(),
					epim.getLongCurrencyPair());

			longOrderIdFuture = longExchange.closeLongPosition(epim.getLongCurrencyPair(), quantityLong, useMarketOrder,
					priceLong);
		}

		CompletableFuture<String> shortOrderIdFuture = null;
		if (!shortOrderPlaced) {
			log.info("Placing short exit order...");

			BigDecimal quantityShort = shortExchange.roundQuantityToStepSizeIfNecessary(true,
					epim.getExitVolumeShort().abs(), epim.getShortCurrencyPair());
			BigDecimal priceShort = shortExchange.roundQuantityToStepSizeIfNecessary(true, epim.getExitPriceShort(),
					epim.getShortCurrencyPair());

			shortOrderIdFuture = shortExchange.closeShortPosition(epim.getShortCurrencyPair(), quantityShort,
					useMarketOrder, priceShort);
		}

		boolean errorClosing = false;

		String longOrderId = null, shortOrderId = null;
		if (longOrderIdFuture != null) {
			try {
				longOrderId = longOrderIdFuture.get();
				epim.setExitLongOrderId(longOrderId);
			} catch (Exception e) {
				log.info("Error placing " + epim.getLongCurrencyPair() + " long order on " + longExchange, e);
				errorClosing = true;
			}
		}

		if (shortOrderIdFuture != null) {
			try {
				shortOrderId = shortOrderIdFuture.get();
				epim.setExitShortOrderId(shortOrderId);
			} catch (Exception e) {
				log.info("Error placing " + epim.getShortCurrencyPair() + " short order on " + shortExchange, e);
				errorClosing = true;
			}
		}

		if (errorClosing) {
			/*
			 * By throwing this exception, we are leaving the pair in the market and the
			 * system will eventually retry whichever order(s) could not be placed. In the
			 * event that these orders somehow do end up being placed, but not returned to
			 * us by the calls above, we'll rely on background position cleanup jobs.
			 */
			throw new OrderPlacementException();
		}

		for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
			l.exitOrdersPlaced(epim);
	}

	/**
	 * This method begins pollers for either the short order, the long order or both
	 * (depending on which hasn't yet been filled). When complete, the poller
	 * returns the statuses, and the orders themselves (if filled) so that the exit
	 * order IDs and prices can be set in the ExchangePairInMarket.
	 * <p>
	 * It's possible one or both statuses may be complete but not filled - e.g.
	 * canceled, expired, rejected. This may be due to manual cancellation of either
	 * position by a human since the order was placed. If both long and short order
	 * are in this situation, we simply need to remove the ExchangePairInMarket. If
	 * only one order is
	 * 
	 * @param epim
	 */
	public void beginExitOrderCompletionPollers(ExchangePairInMarket epim) {
		log.info("Waiting for exit orders to be filled...");

		if (epim.isBothExitOrdersFilled()) {
			finalize(epim);
			return;
		}

		OrderCompletionPoller.startPollers(epim, false, params.orderCompletionMaxExecutionMillis, p -> {
			OrderCompletion longOrderCompletion = p[0];
			OrderCompletion shortOrderCompletion = p[1];

			boolean longFilled = longOrderCompletion.status == FILLED;
			boolean shortFilled = shortOrderCompletion.status == FILLED;
			List<CompletableFuture<?>> allFutures = new ArrayList<>();

			if (longFilled) {
				log.info("{} long order on {} filled.", epim.getLongCurrencyPair(), epim.getLongExchange());
				epim.setExitLongOrderFilled(true);
				epim.setExitVolumeLong(longOrderCompletion.completedOrder.getCumulativeAmount());
				epim.setExitPriceLong(longOrderCompletion.completedOrder.getAveragePrice());
			} else {
				/*
				 * If we're exiting the market, we want to sell our base asset on the long
				 * exchange. If time expired for this order to be filled, it could have been
				 * partially filled. So we first attempt to cancel it, to stop any additional
				 * fulfillment, and then we want to sell the remaining amount at market price
				 * (even if it differs from our limit price) and eat the difference.
				 */
				log.info("{} long order on {} not filled - status {}", epim.getLongCurrencyPair(),
						epim.getLongExchange(), longOrderCompletion.status);
				CompletableFuture<OrderPair> future = cancelOrRevertLongOrder(epim.getLongExchange(),
						epim.getLongCurrencyPair(), longOrderCompletion.orderId, REMAINING);
				allFutures.add(future.handle((orderPair, ex) -> {
					if (ex != null || orderPair.order1 == null) {
						log.error("Encountered exception while trying to cancel / revert a long order; "
								+ "proceeding to remove this pair from the market.", ex);
						epim.setExitLongOrderFilled(false);
					} else {
						Order o1 = orderPair.order1;
						Order o2 = orderPair.order2;
						BigDecimal averagePrice = o2 == null ? o1.getAveragePrice()
								: o1.getAveragePrice().multiply(o1.getCumulativeAmount())
										.add(o2.getAveragePrice().multiply(o2.getCumulativeAmount()))
										.divide(o1.getCumulativeAmount().add(o2.getCumulativeAmount()), DECIMAL64);
						epim.setExitPriceLong(averagePrice);
						epim.setExitLongOrderFilled(true);
					}

					return CompletableFuture.completedFuture(true);
				}));
			}

			if (shortFilled) {
				log.info("{} short order filled on {}", epim.getShortCurrencyPair(), epim.getShortExchange());
				epim.setExitShortOrderFilled(true);
				epim.setExitVolumeShort(shortOrderCompletion.completedOrder.getCumulativeAmount());
				epim.setExitPriceShort(shortOrderCompletion.completedOrder.getAveragePrice());
			} else {
				/*
				 * If we're exiting the market, we want to buy back our base asset on the short
				 * exchange. If time expired for this order to be filled, it could have been
				 * partially filled. So we first attempt to cancel it, to stop any additional
				 * fulfillment, and then we want to buy the remaining amount at market price
				 * (even if it differs from our limit price) and eat the difference.
				 */
				log.info("{} short order on {} not filled - status {}", epim.getShortCurrencyPair(),
						epim.getShortExchange(), shortOrderCompletion.status);
				CompletableFuture<OrderPair> future = cancelOrRevertShortOrder(epim.getShortExchange(),
						epim.getShortCurrencyPair(), shortOrderCompletion.orderId, REMAINING);
				allFutures.add(future.handle((orderPair, ex) -> {
					if (ex != null) {
						log.error("Encountered exception while trying to cancel / revert a short order; "
								+ "proceeding to remove this pair from the market.", ex);
						epim.setExitShortOrderFilled(false);
					} else {
						Order o1 = orderPair.order1;
						Order o2 = orderPair.order2;
						BigDecimal averagePrice = o2 == null ? o1.getAveragePrice()
								: o1.getAveragePrice().multiply(o1.getCumulativeAmount())
										.add(o2.getAveragePrice().multiply(o2.getCumulativeAmount()))
										.divide(o1.getCumulativeAmount().add(o2.getCumulativeAmount()), DECIMAL64);
						epim.setExitPriceShort(averagePrice);
						epim.setExitShortOrderFilled(true);
					}

					return CompletableFuture.completedFuture(true);
				}));
			}

			try {
				CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[] {})).thenAccept(q -> {
					finalize(epim);
				}).join();
			} catch (Exception e) {
				log.error("Error finalizing " + epim.getShortCurrencyPair() + " OrderCompletionPollers", e);
			}
		});
	}

	protected void finalize(ExchangePairInMarket epim) {
		if (epim.getExitTime() == null)
			epim.setExitTime(LocalDateTime.now());

		try {
			log.info(epim.getExitInfo());
			log.info("Exposure on {}: {}", epim.getLongExchange(),
					formatCurrency(epim.getLongCurrencyPair().base, epim.getExitVolumeLong()));
			log.info("Exposure on {}: {}", epim.getShortExchange(),
					formatCurrency(epim.getShortCurrencyPair().base, epim.getExitVolumeShort()));
		} catch (Exception e) {
			log.error("Couldn't print exit info", e);
		}

		boolean removedSuccessfully = marketPairsProvider.getPairsInMarket().removePairFromMarket(epim);
		if (!removedSuccessfully)
			throw new RuntimeException("Couldn't remove the pair from the market ");

		for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
			l.marketPairRemoved(epim);

		for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
			l.orderComplete(epim);
	}

}
