package com.slickapps.blackbird.service;

import static com.slickapps.blackbird.Main.TWO;
import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.FILLED;
import static com.slickapps.blackbird.model.orderCompletion.OrderRollbackType.CUMULATIVE;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.MathContext.DECIMAL64;
import static java.time.LocalDateTime.now;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Balance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.BlackbirdEventListener;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.model.BigDecimalPair;
import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;
import com.slickapps.blackbird.model.SpreadBounds;
import com.slickapps.blackbird.model.TrailingDetails;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletion;
import com.slickapps.blackbird.processes.OrderCompletionPoller;
import com.slickapps.blackbird.service.tradingRule.TradingRuleEvaluationService;
import com.slickapps.blackbird.service.tradingRule.TradingRuleViolationException;
import com.slickapps.blackbird.util.exception.PairsInMarketUpdatedNotification;

public class MarketEntryService extends AbstractMarketService {
	private static final Logger log = LoggerFactory.getLogger(MarketEntryService.class);

	protected Parameters params;
	protected EventListenerProvider eventListenerProvider;
	protected QuoteService quoteService;
	protected SpreadMonitor spreadMonitor;
	protected TrailingStopFilter trailingStopFilter;
	protected MarketPairsProvider marketPairsProvider;
	protected StringPaddingService stringPaddingService = new StringPaddingService();
	protected TradingRuleEvaluationService tradingRuleEvaluationService = new TradingRuleEvaluationService();
	protected AtomicInteger resultCount = new AtomicInteger();

	protected Map<ExchangePairAndCurrencyPair, String> mostRecentValidComparisons = new ConcurrentHashMap<>();

	public MarketEntryService(Parameters params, MarketPairsProvider marketPairsProvider,
			EventListenerProvider eventListenerProvider, QuoteService quoteService, SpreadMonitor spreadMonitor) {
		this.params = params;
		this.marketPairsProvider = marketPairsProvider;
		this.eventListenerProvider = eventListenerProvider;
		this.quoteService = quoteService;
		this.spreadMonitor = spreadMonitor;
		trailingStopFilter = new TrailingStopFilter(params, true);
		resultCount.set(marketPairsProvider.getPairsInMarket().getMaxId() + 1);
	}

	/**
	 * Compare every permutation of {Short Exchange and Currency Pair} with every
	 * other {Long Exchange and Currency Pair} to see if the latest quotes from
	 * those exchanges qualify us for entry into the market. If we find one, we
	 * submit entry orders at both exchanges, and return the new
	 * ExchangePairInMarket object so it can be added to the ExchangePairsInMarket
	 * wrapper and we can begin monitoring for those entry orders to be completed.
	 * 
	 * @param outOfMarketPairs
	 *            All unique {Exchange and Currency Pair} combinations that are not
	 *            already in the market
	 * @return The newly added ExchangePairInMarket, or null if none was found
	 * @throws InterruptedException
	 *             If we are waiting for a
	 * @throws ExecutionException
	 * @throws PairsInMarketUpdatedNotification
	 */
	public ExchangePairInMarket prepareNextPairReadyToEnter(Set<ExchangePairAndCurrencyPair> outOfMarketPairs)
			throws InterruptedException, ExecutionException, PairsInMarketUpdatedNotification {
		for (ExchangePairAndCurrencyPair epcp : outOfMarketPairs) {
			ExchangeAndCurrencyPair i = epcp.getShortExchangeAndCurrencyPair();
			ExchangeAndCurrencyPair j = epcp.getLongExchangeAndCurrencyPair();

			BlackbirdExchange shortExchange = epcp.getShortExchange();
			BlackbirdExchange longExchange = epcp.getLongExchange();

			CurrencyPair shortPair = epcp.getShortCurrencyPair();
			CurrencyPair longPair = epcp.getLongCurrencyPair();

			/*
			 * must be different exchanges, the same (or equivalent) currency pairs, i be
			 * shortable on the specified currency, and both exchanges not temporarily
			 * disabled
			 */
			if (!shortExchange.isEnabled() || !longExchange.isEnabled() || shortExchange.equals(longExchange)
					|| !params.currencyPairsEquivalent(shortPair, longPair) || !i.isShortable()
					|| shortExchange.isDisabledTemporarilyOrNeedsWalletPopulation()
					|| longExchange.isDisabledTemporarilyOrNeedsWalletPopulation())
				continue;

			Optional<Quote> newLongQuote = quoteService.getLatestQuote(j);
			Optional<Quote> newShortQuote = quoteService.getLatestQuote(i);

			/*
			 * If we just started the program and don't have a quote from either exchange
			 * yet, move along
			 */
			if (!newLongQuote.isPresent() || !newShortQuote.isPresent())
				continue;

			QuotePair quotePair = new QuotePair(newLongQuote.get(), newShortQuote.get());

			/*
			 * If the quotes are comparable (i.e. their times are within a few seconds of
			 * one another)
			 */
			if (!quoteService.quotesComparable(quotePair))
				continue;

			/* We have a valid pair of quotes to evaluate; notify our listeners */
			for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
				l.quotePairEvaluation(quotePair, true);

			/*
			 * Evaluate these two quotes to see if they qualify us for entry into the market
			 */
			ExchangePairInMarket epim = null;
			try {
				epim = evaluate(quotePair);
			} catch (SkipEvaluation e) {
				if (e.getCause() != null) {
					log.error(e.getReason(), e.getCause());
				} else if (StringUtils.isNotBlank(e.getReason())) {
					log.info(e.getReason());
				}
				continue;
			}

			/*
			 * We found a new entry into the market. Place both orders, notify our listeners
			 * as such, and return the new epim so we can add it to the
			 * ExchangePairsInMarket wrapper and begin monitoring for its entry orders to be
			 * completed.
			 */
			placeLongAndOrShortOrders(epim);

			for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
				l.entryOrdersPlaced(epim);

			return epim;
		}

		return null;
	}

	/**
	 * Checks for entry opportunity given quotes at two different exchanges and
	 * returns an instance of ExchangePairInMarket if an opportunity is found.
	 * Otherwise throws a SkipEvaluation.
	 * 
	 * @throws InterruptedException
	 *             If a blocked thread waiting on an asynchronous result is
	 *             interrupted
	 */
	private ExchangePairInMarket evaluate(QuotePair quotePair) throws InterruptedException, SkipEvaluation {
		Quote longQuote = quotePair.getLongQuote();
		Quote shortQuote = quotePair.getShortQuote();

		ExchangePairAndCurrencyPair ecp = new ExchangePairAndCurrencyPair(longQuote.getExchangeAndCurrencyPair(),
				shortQuote.getExchangeAndCurrencyPair());
		CurrencyPair longCurrencyPair = ecp.getLongCurrencyPair();
		CurrencyPair shortCurrencyPair = ecp.getShortCurrencyPair();

		/*
		 * Now that I'm trying to enter the market, I want to purchase some of the long
		 * asset (so I'm interested in what people are asking) and I want to borrow and
		 * immediately sell some of the short asset (so I'm interested in what people
		 * are bidding)
		 */
		BigDecimalPair marketPrices = new BigDecimalPair(longQuote.getAsk(), shortQuote.getBid());

		/*
		 * Inspect the current spread between the two quotes and compare it to the
		 * target spread
		 */
		Optional<SpreadBounds> spreadBounds = spreadMonitor.getSpreadBounds(ecp);
		if (!spreadBounds.isPresent())
			throw new SkipEvaluation();

		SpreadBounds sb = spreadBounds.get();
		if (!sb.hasWindowAverage())
			throw new SkipEvaluation();

		BigDecimal currentSpread = quotePair.getSpreadIfEntering();
		Optional<BigDecimal> targetEntrySpread = getEntrySpreadUsingWindowAverage(ecp, sb);
		if (!targetEntrySpread.isPresent())
			throw new SkipEvaluation();

		/*
		 * I am exceeding the target spread! Let's run it by our trailingDetails filter
		 */
		TrailingDetails trailing = getTrailingDetails(ecp);
		boolean trailingStopFilterApproval = trailingStopFilter.evaluate(ecp, currentSpread, targetEntrySpread.get());
		if (!trailingStopFilterApproval)
			throw new SkipEvaluation();

		/* Ensure I have some USD to use to enter the market */
		BigDecimalPair balances = getAndValidateNonZeroBalances(ecp);

		/*
		 * Ensure we aren't going to exceed our overall max exposure for our currency
		 * pairs
		 */
		BigDecimal remainingAvailableExposure = getAndValidateRemainingAvailableExposure(longCurrencyPair,
				shortCurrencyPair);

		/*
		 * Based on our remaining available exposure and how much each exchange will
		 * allow us to leverage, calculate the final transaction amount
		 */
		BigDecimal transactionAmount = getMaxTransactionAmount(ecp, balances, remainingAvailableExposure);

		/*
		 * Calculate the quantities to submit based on the transactionAmount. For those
		 * exchanges that assess fees on the base currency, the quantity received will
		 * be lower; otherwise, the fees are assessed on the final total.
		 */
		BigDecimalPair quantities = getQuantitiesToSubmit(ecp, marketPrices, transactionAmount);

		/*
		 * Get the actual prices necessary to purchase the desired quantities. The limit
		 * prices will be >= than the market prices on the long exchange since we're
		 * buying, and <= the market prices on the short exchange since we're selling.
		 * Since our orders will use these prices, our final transaction amounts will be
		 * slightly different than our desired transactionAmount above, which is why
		 */
		log.info("Entry opportunity found, checking limit prices...");
		BigDecimalPair limitPrices = getLimitPrices(ecp, trailing, quantities);

		ensureLiquidity(ecp, marketPrices, limitPrices);

		/*
		 * Check for violation of exchange order quantity rules - min quantity, min
		 * price, min total
		 */
		try {
			tradingRuleEvaluationService.evaluate(limitPrices, ecp, quantities);
		} catch (TradingRuleViolationException e) {
			throw new SkipEvaluation(e.getMessage());
		}

		/*
		 * Prepare and return the ExchangePairInMarket for addition to the market
		 */
		ExchangePairInMarket epim = new ExchangePairInMarket(ecp);
		epim.setId(resultCount.getAndIncrement());
		epim.setEntryTime(now());

		epim.setEntryVolumeLong(quantities.getLong());
		epim.setEntryPriceLong(limitPrices.getLong());
		epim.setFeePercentageLong(ecp.getLongExchange().getFeePercentage());

		epim.setEntryVolumeShort(quantities.getShort());
		epim.setEntryPriceShort(limitPrices.getShort());
		epim.setFeePercentageShort(ecp.getShortExchange().getFeePercentage());

		epim.setExitTarget(getExitSpread(ecp, currentSpread));
		epim.setExposure(quantities.getLong().multiply(limitPrices.getLong())
				.max(quantities.getShort().multiply(limitPrices.getShort())));

		/* reset trailing data since we just approved this pair for entry */
		trailing.reset();

		return epim;
	}

	private BigDecimalPair getQuantitiesToSubmit(ExchangePairAndCurrencyPair ecp, BigDecimalPair marketPrices,
			BigDecimal transactionAmount) {
		BigDecimal quantityLong = transactionAmount.divide(marketPrices.getLong(), DECIMAL64);
		BigDecimal quantityShort = transactionAmount.divide(marketPrices.getShort(), DECIMAL64);
		quantityLong = ecp.getLongExchange().roundQuantityToStepSizeIfNecessary(true, quantityLong,
				ecp.getLongCurrencyPair());
		quantityShort = ecp.getShortExchange().roundQuantityToStepSizeIfNecessary(true, quantityShort,
				ecp.getShortCurrencyPair());
		BigDecimalPair quantities = new BigDecimalPair(quantityLong, quantityShort);
		return quantities;
	}

	/**
	 * To proceed, we need enough liquidity to ensure our orders will be met quickly
	 * and for a price that doesn't differ too much from what we intended. Thus, we
	 * ensure the prices we need to pay to satisfy our desired quantities (the limit
	 * prices) are no more than (params.maxLimitPriceDifference) different than our
	 * desired market prices.
	 * <p>
	 * If we find a liquidity problem, we intentionally don't reset the trailing
	 * counter since it's possible we can tread water (assuming our spread stays
	 * high and continues to exceed our target) while the market gains liquidity; if
	 * at any time the spread drops below our target, our filter will reset anyway.
	 */
	private void ensureLiquidity(ExchangePairAndCurrencyPair ecp, BigDecimalPair desiredMarketPrices,
			BigDecimalPair actualLimitPrices) throws SkipEvaluation {
		if (actualLimitPrices.getLong().subtract(desiredMarketPrices.getLong())
				.compareTo(params.maxLimitPriceDifference) == 1) {
			log.warn("Opportunity found but not enough liquidity on " + ecp.getLongExchange() + ".");
			log.warn("         Limit long price:  {}, Market long price: {}",
					formatCurrency(ecp.getLongCurrencyPair().counter, desiredMarketPrices.getLong()),
					formatCurrency(ecp.getLongCurrencyPair().counter, actualLimitPrices.getLong()));
			throw new SkipEvaluation();
		}

		if (desiredMarketPrices.getShort().subtract(actualLimitPrices.getShort())
				.compareTo(params.maxLimitPriceDifference) == 1) {
			log.warn("Opportunity found but not enough liquidity on " + ecp.getShortExchange() + ".");
			log.warn("         Limit short price: {}, Market short price: {}",
					formatCurrency(ecp.getShortCurrencyPair().counter, actualLimitPrices.getShort()),
					formatCurrency(ecp.getShortCurrencyPair().counter, desiredMarketPrices.getShort()));
			throw new SkipEvaluation();
		}
	}

	private BigDecimal getAndValidateRemainingAvailableExposure(CurrencyPair longCurrencyPair,
			CurrencyPair shortCurrencyPair) throws SkipEvaluation {
		ExchangePairsInMarket pairsInMarket = marketPairsProvider.getPairsInMarket();

		BigDecimal maxExposureAmount = params.getMaxExposureAmount(shortCurrencyPair.counter)
				.min(params.getMaxExposureAmount(longCurrencyPair.counter));
		BigDecimal currentTotalExposure = pairsInMarket.getTotalExposure(shortCurrencyPair)
				.max(pairsInMarket.getTotalExposure(longCurrencyPair));
		BigDecimal remainingAvailableExposure = maxExposureAmount.subtract(currentTotalExposure);

		if (remainingAvailableExposure.signum() == 0)
			throw new SkipEvaluation(
					"Opportunity found but max exposure for " + shortCurrencyPair.base + " is already met ("
							+ formatCurrency(shortCurrencyPair.base, currentTotalExposure) + "); trade skipped.");
		return remainingAvailableExposure;
	}

	private BigDecimalPair getLimitPrices(ExchangePairAndCurrencyPair ecp, TrailingDetails trailing,
			BigDecimalPair quantities) throws InterruptedException, SkipEvaluation {
		Future<BigDecimal> limPriceLongFuture = ecp.getLongExchange().queryLimitPrice(ecp.getLongCurrencyPair(),
				quantities.getLong(), ASK);
		Future<BigDecimal> limPriceShortFuture = ecp.getShortExchange().queryLimitPrice(ecp.getShortCurrencyPair(),
				quantities.getShort(), BID);

		BigDecimal limPriceLong = null, limPriceShort = null;
		try {
			limPriceLong = limPriceLongFuture.get();
			limPriceShort = limPriceShortFuture.get();
		} catch (ExecutionException e) {
			throw new SkipEvaluation("Error retrieving limit prices from the exchange", e.getCause());
		}

		if (limPriceLong.signum() == 0 || limPriceShort.signum() == 0) {
			log.warn("Opportunity found but error with the order books (limit price is null). Trade canceled");
			log.warn("         Long limit price:  {}", formatCurrency(ecp.getLongCurrencyPair().counter, limPriceLong));
			log.warn("         Short limit price: {}",
					formatCurrency(ecp.getShortCurrencyPair().counter, limPriceShort));
			trailing.reset();
			throw new SkipEvaluation();
		}

		limPriceLong = ecp.getLongExchange().roundPriceToStepSizeIfNecessary(true, limPriceLong,
				ecp.getLongCurrencyPair());
		limPriceShort = ecp.getShortExchange().roundPriceToStepSizeIfNecessary(true, limPriceShort,
				ecp.getShortCurrencyPair());

		BigDecimalPair limitPrices = new BigDecimalPair(limPriceLong, limPriceShort);
		return limitPrices;
	}

	private BigDecimalPair getAndValidateNonZeroBalances(ExchangePairAndCurrencyPair ecp)
			throws InterruptedException, SkipEvaluation {
		BlackbirdExchange longExchange = ecp.getLongExchange();
		BlackbirdExchange shortExchange = ecp.getShortExchange();
		CurrencyPair longCurrencyPair = ecp.getLongCurrencyPair();
		CurrencyPair shortCurrencyPair = ecp.getShortCurrencyPair();

		Future<Balance> longBalanceFuture = longExchange.queryBalance(longCurrencyPair.counter, false);
		Future<Balance> shortBalanceFuture = shortExchange.queryBalance(shortCurrencyPair.counter, false);

		BigDecimal longBalance = null, shortBalance = null;
		try {
			longBalance = longBalanceFuture.get().getAvailable();
			log.info("Current balance at {}: {}", longExchange, formatCurrency(longCurrencyPair.counter, longBalance));
			shortBalance = shortBalanceFuture.get().getAvailable();
			log.info("Current balance at {}: {}", shortExchange,
					formatCurrency(shortCurrencyPair.counter, shortBalance));
		} catch (ExecutionException e) {
			throw new SkipEvaluation("Error retrieving balances from the exchange", e.getCause());
		}

		BigDecimalPair balances = new BigDecimalPair(longBalance, shortBalance);

		if (balances.getLong().signum() == 0) {
			throw new SkipEvaluation("No balance available at " + longExchange + "; trade skipped.");
		}
		if (balances.getShort().signum() == 0) {
			throw new SkipEvaluation("No balance available at " + shortExchange + "; trade skipped.");
		}
		return balances;
	}

	private static class SkipEvaluation extends Exception {
		private static final long serialVersionUID = 4661909864590817162L;

		public SkipEvaluation() {
		}

		public SkipEvaluation(String reason) {
			super(reason);
		}

		public SkipEvaluation(String reason, Throwable t) {
			super(reason, t);
		}

		public String getReason() {
			return getMessage();
		}
	}

	private BigDecimal getMaxTransactionAmount(ExchangePairAndCurrencyPair ecp, BigDecimalPair balances,
			BigDecimal remainingAvailableExposure) {
		BigDecimal shortTransactionAmount = params.getMaxTransactionAmount(ecp.getShortCurrencyPair());
		BigDecimal longTransactionAmount = params.getMaxTransactionAmount(ecp.getLongCurrencyPair());

		BigDecimal transactionAmount = shortTransactionAmount.min(longTransactionAmount);

		if (transactionAmount.compareTo(remainingAvailableExposure) == 1) {
			log.info("Reducing transaction amount to {} to meet remaining available exposure.",
					formatCurrency(ecp.getShortCurrencyPair().counter, transactionAmount));
			transactionAmount = transactionAmount.min(remainingAvailableExposure);
		}

		BigDecimal shortMaxLeverageableAmount = ecp.getShortExchange()
				.getMaxLeveragableAmount(ecp.getShortCurrencyPair(), balances.getShort());
		BigDecimal longMaxLeverageableAmount = ecp.getLongExchange().getMaxLeveragableAmount(ecp.getLongCurrencyPair(),
				balances.getLong());

		if (transactionAmount.compareTo(shortMaxLeverageableAmount) == 1) {
			log.info("Reducing transaction amount to {} to meet remaining available exposure on {}.",
					formatCurrency(ecp.getShortCurrencyPair().counter, shortMaxLeverageableAmount),
					ecp.getShortExchange());
			transactionAmount = shortMaxLeverageableAmount;
		}

		if (transactionAmount.compareTo(longMaxLeverageableAmount) == 1) {
			log.info("Reducing transaction amount to {} to meet remaining available exposure on {}.",
					formatCurrency(ecp.getLongCurrencyPair().counter, longMaxLeverageableAmount),
					ecp.getLongExchange());
			transactionAmount = longMaxLeverageableAmount;
		}

		return transactionAmount;
	}

	public TrailingDetails getTrailingDetails(ExchangePairAndCurrencyPair ecp) {
		return trailingStopFilter.getOrCreateTrailingDetails(ecp);
	}

	/*
	 * Places both long and short orders concurrently at each exchange. If either
	 * fails to be placed, we first check to see if the other succeeded; if so, we
	 * immediately place an inverse order to cancel it (at market price,
	 * unfortunately, since we need it to be filled immediately). In the event
	 * either order placement fails, we throw a PairsInMarketUpdatedNotification so
	 * that we restart our master loop; otherwise, if both succeeded, we return
	 * normally.
	 */
	private void placeLongAndOrShortOrders(ExchangePairInMarket epim) throws PairsInMarketUpdatedNotification {
		log.info("Placing entry orders...");
		BlackbirdExchange longExchange = epim.getLongExchange();
		BlackbirdExchange shortExchange = epim.getShortExchange();

		String longOrderId = null, shortOrderId = null;
		/* Send the orders to the two exchanges (concurrently) */

		BigDecimal entryVolumeLong = epim.getEntryVolumeLong();
		CompletableFuture<String> longOrderIdFuture = longExchange.openLongPosition(epim.getLongCurrencyPair(),
				entryVolumeLong, false, epim.getEntryPriceLong());

		BigDecimal entryVolumeShort = epim.getEntryVolumeShort();
		CompletableFuture<String> shortOrderIdFuture = shortExchange.openShortPosition(epim.getShortCurrencyPair(),
				entryVolumeShort, false, epim.getEntryPriceShort());

		try {
			longOrderId = longOrderIdFuture.get();
			epim.setEntryLongOrderId(longOrderId);
		} catch (Exception e) {
			log.info("Error placing " + epim.getLongCurrencyPair() + " long order on " + longExchange, e);
		}

		try {
			shortOrderId = shortOrderIdFuture.get();
			epim.setEntryShortOrderId(shortOrderId);
		} catch (Exception e) {
			log.info("Error placing " + epim.getShortCurrencyPair() + " short order on " + shortExchange, e);
		}

		if (longOrderId == null || shortOrderId == null) {
			/*
			 * at least one did not complete successfully; cancel the other if it completed
			 */
			if (longOrderId != null) {
				try {
					cancelOrRevertLongOrder(longExchange, epim.getLongCurrencyPair(), longOrderId, CUMULATIVE);
				} catch (Exception e) {
					log.error("Couldn't place a market order at long exchange " + longExchange
							+ " to revert market entry; manual intervention required.", e);
				}
			}

			if (shortOrderId != null) {
				try {
					cancelOrRevertShortOrder(shortExchange, epim.getShortCurrencyPair(), shortOrderId, CUMULATIVE);
				} catch (Exception e) {
					log.error("Couldn't place a market order at short exchange " + shortExchange
							+ " to revert market entry; manual intervention required.", e);
				}
			}

			/*
			 * Might be save to continue to next currency pair, but just to be safe let's
			 * reset here
			 */
			throw new PairsInMarketUpdatedNotification();
		}

	}

	public void beginEntryOrderCompletionPollers(ExchangePairInMarket epim) {
		log.info("Beginning polling for entry orders to be completed...");

		OrderCompletionPoller.startPollers(epim, true, params.orderCompletionMaxExecutionMillis, p -> {
			OrderCompletion longOrderCompletion = p[0];
			OrderCompletion shortOrderCompletion = p[1];

			boolean longFilled = longOrderCompletion.status == FILLED;
			boolean shortFilled = shortOrderCompletion.status == FILLED;

			if (longFilled) {
				log.info("{} entry long order filled on {}.", epim.getLongCurrencyPair(), epim.getLongExchange());
				epim.setEntryLongOrderFilled(true);
				epim.setEntryPriceLong(longOrderCompletion.completedOrder.getAveragePrice());
				epim.setEntryVolumeLong(longOrderCompletion.completedOrder.getCumulativeAmount());
			} else {
				log.info("{} entry long order on {} not filled - status {}", epim.getLongCurrencyPair(),
						epim.getLongExchange(), longOrderCompletion.status);
				if (longOrderCompletion.errorOccurred != null)
					log.error("Long Error:", longOrderCompletion.errorOccurred);
			}

			if (shortFilled) {
				log.info("{} entry short order filled on {}", epim.getShortCurrencyPair(), epim.getShortExchange());
				epim.setEntryShortOrderFilled(true);
				epim.setEntryPriceShort(shortOrderCompletion.completedOrder.getAveragePrice());
				epim.setEntryVolumeShort(shortOrderCompletion.completedOrder.getCumulativeAmount());
			} else {
				log.info("{} entry short order on {} not filled - status {}", epim.getShortCurrencyPair(),
						epim.getShortExchange(), shortOrderCompletion.status);
				if (shortOrderCompletion.errorOccurred != null)
					log.error("Short Error:", shortOrderCompletion.errorOccurred);
			}

			if (longFilled && shortFilled) {
				for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
					l.entryOrdersFilled(epim);
			} else {
				/*
				 * Let's wait a little bit here so that orders show up at some exchanges - e.g.
				 * HitBTC
				 */
				try {
					Thread.sleep(30000);
				} catch (InterruptedException e1) {
				}

				log.warn("Removing {} pair from market since both entry orders could not be filled.",
						epim.getShortCurrencyPair());

				cleanupIncompleteOrderPair(epim);
			}
		});
	}

	public Optional<BigDecimal> getEntrySpreadUsingWindowAverage(ExchangePairAndCurrencyPair ecp,
			SpreadBounds spreadBounds) {
		if (spreadBounds == null)
			return Optional.empty();

		BlackbirdExchange longExchange = ecp.getLongExchange();
		BlackbirdExchange shortExchange = ecp.getShortExchange();

		/*
		 * Center at zero if we don't want to adapt to the average over some timeframe -
		 * CPB
		 */
		BigDecimal windowAverage = BigDecimal.ZERO;
		if (params.adaptToWindowAverage) {
			windowAverage = spreadBounds.getWindowAverage();
			if (windowAverage == null)
				return Optional.empty();
		}

		return Optional.of(windowAverage.add(longExchange.getFeePercentage().add(shortExchange.getFeePercentage())
				.add(params.targetProfitPercentage.divide(TWO, DECIMAL64))));
	}

	public BigDecimal getExitSpread(ExchangePairAndCurrencyPair ecp, BigDecimal entrySpread) {
		BlackbirdExchange longExchange = ecp.getLongExchange();
		BlackbirdExchange shortExchange = ecp.getShortExchange();
		BigDecimal percentDiff = params.targetProfitPercentage
				.add(TWO.multiply(longExchange.getFeePercentage().add(shortExchange.getFeePercentage())));
		return entrySpread.subtract(percentDiff);
	}

	public void cleanupIncompleteOrderPair(ExchangePairInMarket epim) {
		BlackbirdExchange longExchange = epim.getLongExchange();
		String longOrderId = epim.getEntryLongOrderId();

		BlackbirdExchange shortExchange = epim.getShortExchange();
		String shortOrderId = epim.getEntryShortOrderId();

		/*
		 * One or both positions couldn't be filled for whatever reason. Either one may
		 * have been partially filled. So first, we cancel both, in case either one is
		 * still in the process of being filled; if uncancelable (due to the fact it was
		 * already filled, or never correctly placed, or for any other reason) we ignore
		 * the error. Then we attempt to roll back any filled amount by selling any
		 * amount we may have already purchased (on the long exchange) or buying any
		 * amount we may have shorted (on the short exchange).
		 */

		if (shortOrderId != null) {
			CurrencyPair shortCurrencyPair = epim.getShortCurrencyPair();
			try {
				shortExchange.cancelOrder(shortCurrencyPair, shortOrderId).get();
			} catch (Exception e) {
				log.warn("Couldn't cancel " + shortCurrencyPair + " short order ID " + shortOrderId + " at exchange "
						+ shortExchange + ". It may have already been filled; attempting to close position next.", e);
			}

			try {
				Optional<Order> orderOpt = shortExchange.queryOrder(shortCurrencyPair, shortOrderId).get();
				if (orderOpt.isPresent()) {
					shortExchange
							.closeShortPosition(shortCurrencyPair, orderOpt.get().getCumulativeAmount(), true, null)
							.get();
					log.error("{} short order placed.", shortCurrencyPair);
				}
			} catch (Exception e) {
				log.error("Couldn't place " + shortCurrencyPair + " short order at exchange " + shortExchange
						+ "; manual intervention required.", e);
			}
		}

		if (longOrderId != null) {
			CurrencyPair longCurrencyPair = epim.getLongCurrencyPair();
			try {
				longExchange.cancelOrder(longCurrencyPair, longOrderId).get();
			} catch (Exception e) {
				log.warn("Couldn't cancel " + longCurrencyPair + " long order ID " + longOrderId + " at exchange "
						+ longExchange + ". It may have already been filled; attempting to close position next.", e);
			}

			try {
				Optional<Order> orderOpt = longExchange.queryOrder(longCurrencyPair, longOrderId).get();
				if (orderOpt.isPresent()) {
					longExchange.closeLongPosition(longCurrencyPair, orderOpt.get().getCumulativeAmount(), true, null)
							.get();
					log.error("{} long order placed.", longCurrencyPair);
				}
			} catch (Exception e) {
				log.error("Couldn't place " + longCurrencyPair + " long order at exchange " + longExchange
						+ "; manual intervention required.", e);
			}
		}

		marketPairsProvider.getPairsInMarket().removePairFromMarket(epim);
	}

}
