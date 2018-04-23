package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.exchanges.OperationType.PLACE_LIMIT_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.PLACE_MARKET_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER_BOOK;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static com.slickapps.blackbird.util.FormatUtil.getQuantityFormatter;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;
import static org.knowm.xchange.kraken.KrakenAdapters.adaptCurrencyPair;
import static org.knowm.xchange.kraken.dto.trade.KrakenStandardOrder.getMarketOrderBuilder;
import static org.knowm.xchange.kraken.dto.trade.KrakenType.BUY;
import static org.knowm.xchange.kraken.dto.trade.KrakenType.SELL;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.kraken.KrakenAdapters;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.trade.KrakenOpenPosition;
import org.knowm.xchange.kraken.dto.trade.KrakenOrder;
import org.knowm.xchange.kraken.dto.trade.KrakenOrderDescription;
import org.knowm.xchange.kraken.dto.trade.KrakenOrderResponse;
import org.knowm.xchange.kraken.dto.trade.KrakenStandardOrder;
import org.knowm.xchange.kraken.dto.trade.KrakenStandardOrder.KrakenOrderBuilder;
import org.knowm.xchange.kraken.dto.trade.KrakenType;
import org.knowm.xchange.kraken.dto.trade.results.KrakenQueryOrderResult;
import org.knowm.xchange.kraken.service.KrakenTradeServiceRaw;
import org.knowm.xchange.service.marketdata.MarketDataService;

import com.slickapps.blackbird.Main;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;

public class Kraken extends AbstractBlackbirdExchange {

	/* */
	private static final long OPEN_POSITION_CLEANUP_VALID_FOR_MILLIS = 15 * 60 * 1000;

	private long openPositionCleanupIntervalMillis;

	public Kraken(Parameters params) {
		initialize(params);
		this.openPositionCleanupIntervalMillis = params.getLong(propertyPrefix + "PositionCleanupIntervalMillis",
				10 * 60 * 1000L);
	}

	@Override
	protected Exchange createExchangeInstance() {
		return new KrakenExchange();
	}

	@Override
	public boolean isExceptionRetryable(Exception e) {
		if (defaultIfBlank(e.getMessage(), "").contains("EService:Unavailable"))
			return true;
		return false;
	}

	@Override
	public CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			KrakenTradeServiceRaw tradeService = (KrakenTradeServiceRaw) exchange.getTradeService();
			try {
				KrakenQueryOrderResult result = tradeService.queryKrakenOrdersResult(false, null, orderId);
				if (!result.isSuccess())
					throw new IllegalArgumentException("An error occurred retrieving order ID " + orderId + " - "
							+ Arrays.toString(result.getError()));
				Map<String, KrakenOrder> orders = result.getResult();
				if (orders.isEmpty())
					return Optional.empty();

				KrakenOrder krakenOrder = orders.get(orderId);

				Order order = KrakenAdapters.adaptOrder(orderId, krakenOrder);
				return Optional.of(order);
			} catch (ExchangeException e) {
				if (e.getMessage() != null && e.getMessage().contains("Invalid order"))
					return Optional.empty();
				throw e;
			}
		}, getRateLimitersForOperation(QUERY_ORDER));
	}

	@Override
	public Map<String, Runnable> getBackgroundJobs(MarketPairsProvider marketPairsProvider) {
		Kraken kraken = this;
		Map<String, Runnable> jobs = new HashMap<>();

		Runnable positionCleanupJob = new Runnable() {
			@Override
			public void run() {
				while (Main.stillRunning) {
					try {
						if (!kraken.isDisabledTemporarily()) {
							long closeBeforeTimestamp = System.currentTimeMillis()
									- OPEN_POSITION_CLEANUP_VALID_FOR_MILLIS;
							kraken.runPositionCleanup(marketPairsProvider, closeBeforeTimestamp);
						}
						Thread.sleep(openPositionCleanupIntervalMillis);
					} catch (InterruptedException e) {
						log.debug("{} interrupted, exiting", getClass().getSimpleName());
						return;
					}
				}
			}
		};
		jobs.put("OpenPositionCleanup", positionCleanupJob);

		return jobs;
	}

	protected List<String> runPositionCleanup(MarketPairsProvider marketPairsProvider, long closeBeforeTimestamp) {
		List<String> resultOrderIds = new ArrayList<>();

		ExchangePairsInMarket exchangePairsInMarket = marketPairsProvider.getPairsInMarket();
		if (exchangePairsInMarket == null)
			return resultOrderIds;

		// get all order IDs we're managing in market
		Set<ExchangePairInMarket> pairsAtKraken = exchangePairsInMarket.getPairsForExchange(this, false, true);
		Set<String> orderIdsInMarket = pairsAtKraken.stream().map(p -> p.getEntryShortOrderId()).collect(toSet());
		Set<CurrencyPair> currencyPairsInMarket = pairsAtKraken.stream().map(p -> p.getShortCurrencyPair())
				.collect(toSet());

		log.info("Querying for Kraken open positions...");
		// get all current open positions at the exchange
		Map<String, KrakenOpenPosition> openPositions = getOpenPositions();

		/*-
		 * We can open a pair in market for the same currency pair twice, on different exchanges. Because Kraken closes
		 * items in first-in-first-out order, it's possible we want to exit the second but our purchase gets applied
		 * to the first. This is ok as long as we understand that the total sum of open positions for any currency pair
		 * equals the sum of all pairs in market for that currency pair. However the positions won't directly correlate
		 * to the orders. So given the above we only deem it safe to run this process for currency pairs that aren't
		 * in the market at all. 
		 * 
		 * build a list of all open positions whose currency pairs are not linked to ANY of our current market orders
		 * 		(these are safe to close)
		 * group these positions by currency, and then get the earliest (chronologically) of each group
		 * for each of these earliest positions
		 * 		if a leveraged order with the same volume intended to close that position doesn't already exist 
		 * 			create a new long order with the correct volume to close the position
		 */

		Map<Currency, KrakenOpenPosition> earliestPositionsByCurrency = new HashMap<>();
		openPositions.entrySet().stream().map(p -> p.getValue()) //
				.filter(p -> !currencyPairsInMarket.contains(adaptCurrencyPair(p.getAssetPair())))
				.sorted(new Comparator<KrakenOpenPosition>() {
					@Override
					public int compare(KrakenOpenPosition o1, KrakenOpenPosition o2) {
						return Long.compare(o1.getTradeUnixTimestamp(), o2.getTradeUnixTimestamp());
					}
				}).forEachOrdered(openPosition -> {
					earliestPositionsByCurrency.computeIfAbsent(adaptCurrencyPair(openPosition.getAssetPair()).base,
							p -> openPosition);
				});

		if (earliestPositionsByCurrency.isEmpty()) {
			log.info("No Kraken positions found requiring cleanup.");
			return resultOrderIds;
		}

		log.info("Querying for Kraken open orders...");
		// get all current open orders at the exchange
		Map<String, KrakenOrder> openOrders = getOpenOrders();

		// for each of the earliest positions for each currency
		outer: //
		for (KrakenOpenPosition pos : earliestPositionsByCurrency.values()) {
			/*
			 * If that earliest position is still being filled in the market, move on to the
			 * next currency
			 */
			if (orderIdsInMarket.contains(pos.getOrderTxId()))
				continue;

			/*
			 * only settle positions which were opened prior to N minutes ago so we don't
			 * conflict with any active processes within this app
			 */
			if (pos.getTradeUnixTimestamp() >= closeBeforeTimestamp)
				continue;

			KrakenType positionType = pos.getType();
			KrakenType orderType = positionType == BUY ? SELL : BUY;

			CurrencyPair posCurrencyPair = KrakenAdapters.adaptCurrencyPair(pos.getAssetPair());

			if (positionType == SELL && !getCurrencyPairsForShortPositions().contains(posCurrencyPair)) {
				continue;
			} else if (positionType == BUY && !getCurrencyPairsForLongPositions().contains(posCurrencyPair)) {
				continue;
			}

			BigDecimal totalVolume = pos.getVolume().subtract(pos.getVolumeClosed());

			log.info("Searching for Kraken {} order to satisfy {} position with volume {}", orderType, posCurrencyPair,
					formatCurrency(posCurrencyPair.base, totalVolume));

			/* search for any outstanding order that will close it */
			for (KrakenOrder order : openOrders.values()) {
				KrakenOrderDescription desc = order.getOrderDescription();
				CurrencyPair orderCurrencyPair = KrakenAdapters.adaptCurrencyPair(desc.getAssetPair());
				String leverage = desc.getLeverage();
				if (desc.getType() == orderType && orderCurrencyPair.base.equals(posCurrencyPair.base)
						&& isNotBlank(leverage) && !"1".equals(leverage) && !"1:1".equals(leverage)) {
					/*
					 * found an existing close position order; let it be filled and continue to next
					 * position
					 */
					log.info("Existing Kraken order found.");
					continue outer;
				}
			}

			/*
			 * didn't find an existing order; create one
			 */

			log.info("No Kraken order found, submitting order to close position.");
			/*
			 * Do not call with retry since Kraken is insanely slow to respond and this may
			 * open multiple orders; instead, attempt it once, if it works, great, if not,
			 * swallow the error and wait for our next instantiation where we check for the
			 * presence of open orders again.
			 */
			try {
				Integer maxLeverage = getMaxLeverage(posCurrencyPair);
				if (maxLeverage == null)
					throw new Exception("No max leverage defined for currency pair " + posCurrencyPair);
				KrakenOrderBuilder orderBuilder = getMarketOrderBuilder(posCurrencyPair, orderType, totalVolume)
						.withLeverage(maxLeverage.toString());
				callSync(() -> {
					KrakenTradeServiceRaw tradeService = (KrakenTradeServiceRaw) exchange.getTradeService();
					KrakenStandardOrder order = orderBuilder.buildOrder();
					KrakenOrderResponse orderResponse = tradeService.placeKrakenOrder(order);
					log.info("Kraken {} order submitted, transaction IDs returned: {}", orderType,
							orderResponse.getTransactionIds());
					return null;
				});
			} catch (Exception e) {
				log.warn("Error attempting to place a close position order; ignoring and will retry in another "
						+ formatDurationWords(openPositionCleanupIntervalMillis, true, true) + ".", e);
			}
		}

		return resultOrderIds;
	}

	public BigDecimal getCurrentAsk(CurrencyPair currencyPair) {
		BigDecimal ask = callSyncWithRetry(() -> {
			MarketDataService marketDataService = exchange.getMarketDataService();
			Ticker ticker = marketDataService.getTicker(currencyPair);
			if (ticker.getBid() == null || ticker.getAsk() == null) {
				log.warn("Exchange {} returned a null bid or ask, ignoring result...", exchange);
				throw new Exception("Null bid/ask returned by exchange, ignoring result");
			}
			return ticker.getAsk();
		}, getRateLimitersForOperation(OperationType.QUERY_TICKER));
		return ask;
	}

	protected Map<String, KrakenOrder> getOpenOrders() {
		Map<String, KrakenOrder> openOrders = callSyncWithRetry(() -> {
			KrakenTradeServiceRaw tradeService = (KrakenTradeServiceRaw) exchange.getTradeService();
			return tradeService.getKrakenOpenOrders();
		}, getRateLimitersForOperation(QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS));
		return openOrders;
	}

	protected Map<String, KrakenOpenPosition> getOpenPositions() {
		Map<String, KrakenOpenPosition> openPositions = callSyncWithRetry(() -> {
			KrakenTradeServiceRaw tradeService = (KrakenTradeServiceRaw) exchange.getTradeService();
			return tradeService.getOpenPositions();
		}, getRateLimitersForOperation(OperationType.QUERY_OPEN_POSITIONS));
		return openPositions;
	}

	@Override
	protected CompletableFuture<String> openShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		log.info("Trying to open a short {} position on Kraken: {}...", currencyPair,
				getQuantityFormatter().format(quantity));
		return sendLeveragedOrderWithRetry(ASK, currencyPair, quantity, useMarketOrder, limitPriceOverride);
	}

	@Override
	protected CompletableFuture<String> closeShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		log.info("Trying to close a short {} position on Kraken: {}...", currencyPair,
				getQuantityFormatter().format(quantity));
		return sendLeveragedOrderWithRetry(BID, currencyPair, quantity, useMarketOrder, limitPriceOverride);
	}

	protected CompletableFuture<String> sendLeveragedOrderWithRetry(OrderType orderType, CurrencyPair currencyPair,
			BigDecimal quantity, boolean useMarketOrder, BigDecimal limitPriceOverride) {
		BigDecimal limitPrice = limitPriceOverride;
		if (!useMarketOrder && limitPriceOverride == null)
			limitPrice = callSyncWithRetry(queryLimitPriceInternal(currencyPair, quantity, orderType),
					getRateLimitersForOperation(QUERY_ORDER_BOOK));
		BigDecimal finalPrice = limitPrice;

		log.info("Price: {}", useMarketOrder ? "market price" : formatCurrency(currencyPair.counter, finalPrice));

		return callAsyncWithRetry(() -> {
			if (params.demoMode)
				return placeDummyOrder(quantity, finalPrice, orderType);

			KrakenTradeServiceRaw tradeService = (KrakenTradeServiceRaw) exchange.getTradeService();
			KrakenOrderBuilder orderBuilder = useMarketOrder
					? KrakenStandardOrder.getMarketOrderBuilder(currencyPair, orderType == ASK ? SELL : BUY, quantity)
					: KrakenStandardOrder.getLimitOrderBuilder(currencyPair, orderType == ASK ? SELL : BUY,
							finalPrice.toString(), quantity);

			Integer maxLeverage = getMaxLeverage(currencyPair);
			if (maxLeverage != null)
				orderBuilder = orderBuilder.withLeverage(String.valueOf(maxLeverage));

			KrakenStandardOrder order = orderBuilder.buildOrder();
			KrakenOrderResponse orderResponse = tradeService.placeKrakenOrder(order);
			log.debug("Order placed: {}", orderResponse);

			List<String> transactionIds = orderResponse.getTransactionIds();
			return transactionIds.get(0);
		}, getRateLimitersForOperation(useMarketOrder ? PLACE_MARKET_ORDER : PLACE_LIMIT_ORDER));
	}

}
