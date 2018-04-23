package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.exchanges.OperationType.CANCEL_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_OPEN_ORDERS_FOR_CURRENCY_PAIR;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.binance.BinanceAdapters;
import org.knowm.xchange.binance.BinanceExchange;
import org.knowm.xchange.binance.dto.BinanceException;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.BinanceExchangeInfo;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.Filter;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.RateLimit;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.Symbol;
import org.knowm.xchange.binance.dto.trade.BinanceOrder;
import org.knowm.xchange.binance.service.BinanceCancelOrderParams;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.binance.service.BinanceTradeService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParamCurrencyPair;
import org.knowm.xchange.service.trade.params.orders.OpenOrdersParams;

import com.google.common.util.concurrent.RateLimiter;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.service.ExchangeCalculationService.UserTradesAggregateResult;
import com.slickapps.blackbird.util.RateLimiterProfile;

public class Binance extends AbstractBlackbirdExchange {

	private static final Map<org.knowm.xchange.binance.dto.trade.OrderStatus, OrderStatus> ORDER_STATUS_MAP = new EnumMap<>(
			org.knowm.xchange.binance.dto.trade.OrderStatus.class);

	static {
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.NEW, OrderStatus.NEW);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.PARTIALLY_FILLED,
				OrderStatus.PARTIALLY_FILLED);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.FILLED, OrderStatus.FILLED);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.CANCELED, OrderStatus.CANCELED);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.PENDING_CANCEL,
				OrderStatus.PENDING_CANCEL);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.REJECTED, OrderStatus.REJECTED);
		ORDER_STATUS_MAP.put(org.knowm.xchange.binance.dto.trade.OrderStatus.EXPIRED, OrderStatus.EXPIRED);
	}

	private static OrderStatus adaptBinanceOrderStatus(org.knowm.xchange.binance.dto.trade.OrderStatus status) {
		return ORDER_STATUS_MAP.get(status);
	}

	private static final String RATE_LIMITER_TYPE_REQUESTS = "REQUESTS";
	private static final String RATE_LIMITER_TYPE_ORDERS = "ORDERS";

	public static Map<String, CurrencyPair> symbolMap;

	CustomBinanceTradeService customTradeService;

	public Binance(Parameters params) {
		initialize(params);
		if (!isEnabled())
			return;

		if (exchange != null) {
			customTradeService = new CustomBinanceTradeService(exchange);
		}

		initializeLocal();
	}

	private void initializeLocal() {
		log.info("Querying dynamic Binance configuration...");

		BinanceMarketDataService marketDataService = (BinanceMarketDataService) exchange.getMarketDataService();
		BinanceExchangeInfo exchangeInfo;
		try {
			exchangeInfo = marketDataService.getExchangeInfo();
		} catch (IOException e) {
			throw new RuntimeException("Couldn't initialize Binance", e);
		}
		Symbol[] symbols = exchangeInfo.getSymbols();

		symbolMap = new HashMap<>();

		for (Symbol bs : symbols) {
			CurrencyPair cp = new CurrencyPair(bs.getBaseAsset(), bs.getQuoteAsset());
			symbolMap.put(bs.getSymbol(), cp);

			for (Filter filter : bs.getFilters()) {
				if ("LOT_SIZE".equals(filter.getFilterType())) {
					if (filter.getMinQty() != null)
						getOrCreateTradingRule(cp).setMinQuantity(new BigDecimal(filter.getMinQty()));
					if (filter.getStepSize() != null)
						getOrCreateTradingRule(cp).setStepSizeForQuantity(new BigDecimal(filter.getStepSize()));
				} else if ("PRICE_FILTER".equals(filter.getFilterType())) {
					if (filter.getMinPrice() != null)
						getOrCreateTradingRule(cp).setMinPrice(new BigDecimal(filter.getMinPrice()));
					if (filter.getTickSize() != null)
						getOrCreateTradingRule(cp).setStepSizeForPrice(new BigDecimal(filter.getTickSize()));
				} else if ("MIN_NOTIONAL".equals(filter.getFilterType())) {
					if (filter.getMinNotional() != null)
						getOrCreateTradingRule(cp).setMinTotal(new BigDecimal(filter.getMinNotional()));
				}
			}
		}
	}

	protected RateLimiterProfile[] getRateLimitersForOperation(OperationType type, Object... operationMethodArgs) {
		RateLimiter ordersLimiter = getOrDefaultRateLimiter(RATE_LIMITER_TYPE_ORDERS);
		RateLimiter requestsLimiter = getOrDefaultRateLimiter(RATE_LIMITER_TYPE_REQUESTS);
		RateLimiterProfile result = null;

		switch (type) {
		case PLACE_LIMIT_ORDER:
		case PLACE_MARKET_ORDER:
		case CANCEL_ORDER:
			result = new RateLimiterProfile(ordersLimiter, 1);
			break;
		case QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS:
			result = new RateLimiterProfile(requestsLimiter, exchange.getExchangeSymbols().size() / 2);
			break;
		case QUERY_EXCHANGE_INFO:
		case QUERY_FOR_QUOTE:
		case QUERY_OPEN_POSITIONS:
		case QUERY_OPEN_ORDERS_FOR_CURRENCY_PAIR:
		case QUERY_ORDER:
		case QUERY_ORDER_STATUS:
		case QUERY_TICKER:
			result = new RateLimiterProfile(requestsLimiter, 1);
			break;
		case QUERY_ORDER_BOOK:
			int count = 100;
			if (operationMethodArgs != null && operationMethodArgs.length >= 1
					&& operationMethodArgs[0] instanceof Number)
				count = ((Number) operationMethodArgs[0]).intValue();
			result = new RateLimiterProfile(requestsLimiter, count <= 100 ? 1 : count <= 500 ? 5 : 10);
			break;
		case QUERY_TRADE_HISTORY:
		case QUERY_WALLET:
			result = new RateLimiterProfile(requestsLimiter, 5);
			break;
		default:
			throw new AssertionError("Unhandled type " + type);
		}

		RateLimiterProfile[] results = new RateLimiterProfile[] { result };
		return results;
	}

	@Override
	protected Exchange createExchangeInstance() {
		return new BinanceExchange() {
			@Override
			public TradeService getTradeService() {
				return customTradeService;
			}
		};
	}

	@Override
	public boolean isExceptionRetryable(Exception e) {
		if (e instanceof BinanceException) {
			BinanceException be = (BinanceException) e;
			// see https://www.reddit.com/r/binance/comments/7h9exv/api_request_rate_limits/
			if (be.code == 418 || be.code == 429)
				return true;
		}

		return false;
	}

	@Override
	public CompletableFuture<Boolean> cancelOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			if (log.isInfoEnabled())
				log.info("Trying to cancel the Binance order with ID {}...", orderId);

			TradeService tradeService = exchange.getTradeService();
			return tradeService.cancelOrder(new BinanceCancelOrderParams(currencyPair, orderId));
		}, getRateLimitersForOperation(CANCEL_ORDER));
	}

	public CompletableFuture<List<LimitOrder>> queryAllOrders(CurrencyPair currencyPair, Optional<Integer> limit) {
		return callAsyncWithRetry(() -> {
			BinanceTradeService tradeService = (BinanceTradeService) exchange.getTradeService();
			List<BinanceOrder> rawOrders = tradeService.allOrders(currencyPair, null, limit.orElse(null), null,
					System.currentTimeMillis());
			List<LimitOrder> orders = rawOrders.stream()
					.map(o -> new LimitOrder(BinanceAdapters.convert(o.side), o.origQty, o.executedQty,
							symbolMap.get(o.symbol), Long.toString(o.orderId), o.getTime(), o.price))
					.collect(Collectors.toList());
			return orders;
		}, getRateLimitersForOperation(
				currencyPair != null ? QUERY_OPEN_ORDERS_FOR_CURRENCY_PAIR : QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS));
	}

	@Override
	public CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			try {
				Optional<Order> opt = queryOrderWithoutAggregatesWithinRate(currencyPair, orderId);
				if (!opt.isPresent())
					return Optional.empty();

				Order order = opt.get();
				UserTrades tradeHistory = getTradeHistoryNow(currencyPair);

				UserTradesAggregateResult aggRes = calcService.analyzeTrades(currencyPair, orderId,
						tradeHistory.getUserTrades());
				if (aggRes.foundMatchingTrades) {
					order.setAveragePrice(aggRes.averagePrice);
					order.setCumulativeAmount(aggRes.getSumTradeQuantitiesAfterFees(currencyPair));
					order.setFee(aggRes.sumFees);
				}

				return Optional.of(order);
			} catch (ExchangeException e) {
				if (e.getMessage() != null && e.getMessage().contains("Invalid order")) {
					throw new IllegalArgumentException("Order ID " + orderId + " not found");
				} else {
					throw e;
				}
			}
		});
	}

	protected Optional<Order> queryOrderWithoutAggregatesWithinRate(CurrencyPair currencyPair, String orderId)
			throws Exception {
		Order order = callSyncWithRetry(() -> {
			Order o = customTradeService.getOrder(currencyPair, orderId);
			return o;
		}, getRateLimitersForOperation(QUERY_ORDER));

		return Optional.ofNullable(order);
	}

	public CompletableFuture<UserTrades> queryTradeHistory(CurrencyPair currencyPair) {
		return callAsyncWithRetry(() -> {
			return getTradeHistoryNow(currencyPair);
		});
	}

	private UserTrades getTradeHistoryNow(CurrencyPair currencyPair) {
		/*
		 * Retrieve the trades since the getOrder() above doesn't return our average
		 * price
		 */
		TradeHistoryParams thp = customTradeService.createTradeHistoryParams();
		if (thp instanceof TradeHistoryParamCurrencyPair) {
			TradeHistoryParamCurrencyPair thcp = (TradeHistoryParamCurrencyPair) thp;
			thcp.setCurrencyPair(currencyPair);
		}

		UserTrades tradeHistory = callSyncWithRetry(() -> {
			UserTrades th = customTradeService.getTradeHistory(thp);
			return th;
		}, getRateLimitersForOperation(OperationType.QUERY_TRADE_HISTORY));
		return tradeHistory;
	}

	static class CustomBinanceTradeService extends BinanceTradeService {

		protected CustomBinanceTradeService(Exchange exchange) {
			super(exchange);
		}

		public Order getOrder(CurrencyPair pair, String orderId) throws BinanceException, IOException {
			try {
				BinanceOrder o = orderStatus(pair, Long.parseLong(orderId), null, null, System.currentTimeMillis());
				// nulls for average amt or fee
				LimitOrder result = new LimitOrder(BinanceAdapters.convert(o.side), o.origQty, pair,
						Long.toString(o.orderId), o.getTime(), o.price);
				result.setCumulativeAmount(o.executedQty);
				result.setOrderStatus(adaptBinanceOrderStatus(o.status));
				return result;
			} catch (BinanceException e) {
				if (e.getMessage().contains("Order does not exist"))
					return null;
				throw e;
			}
		}

		@Override
		public OpenOrders getOpenOrders() {
			try {
				return getAllOpenOrders();
			} catch (BinanceException | IOException e) {
				throw new RuntimeException("Couldn't get all open orders", e);
			}
		}

		@Override
		public OpenOrders getOpenOrders(OpenOrdersParams params) throws IOException {
			if (params instanceof OpenOrdersParamCurrencyPair
					&& ((OpenOrdersParamCurrencyPair) params).getCurrencyPair() != null)
				return super.getOpenOrders(params);

			return getAllOpenOrders();
		}

		public OpenOrders getAllOpenOrders() throws BinanceException, IOException {
			Long recvWindow = (Long) exchange.getExchangeSpecification()
					.getExchangeSpecificParametersItem("recvWindow");
			List<BinanceOrder> binanceOpenOrders = binance.openOrders(null, recvWindow, getTimestamp(), super.apiKey,
					super.signatureCreator);
			List<LimitOrder> openOrders = binanceOpenOrders.stream().map(o -> {
				return new LimitOrder(BinanceAdapters.convert(o.side), o.origQty, o.executedQty,
						symbolMap.get(o.symbol), Long.toString(o.orderId), o.getTime(), o.price);
			}).collect(Collectors.toList());
			return new OpenOrders(openOrders);
		}

	}

	@Override
	public Map<String, Runnable> getBackgroundJobs(MarketPairsProvider provider) {
		Map<String, Runnable> jobs = new HashMap<>();
		jobs.put("BinanceRateLimitUpdater", new Runnable() {
			@Override
			public void run() {
				while (true) {
					log.info("Updating Binance dynamic rate limits...");

					try {
						BinanceMarketDataService marketDataService = (BinanceMarketDataService) exchange
								.getMarketDataService();
						BinanceExchangeInfo exchangeInfo = marketDataService.getExchangeInfo();
						if (ArrayUtils.isNotEmpty(exchangeInfo.getRateLimits())) {
							// calculate slowest rate across all limits and go with this for now
							Map<String, Double> fastestRateMap = new HashMap<>();

							fastestRateMap.put(RATE_LIMITER_TYPE_ORDERS, Double.MAX_VALUE);
							fastestRateMap.put(RATE_LIMITER_TYPE_REQUESTS, Double.MAX_VALUE);

							for (RateLimit l : exchangeInfo.getRateLimits()) {
								String rateLimitType = l.getRateLimitType(); // "ORDERS" or "REQUESTS"
								Double val = fastestRateMap.get(rateLimitType);
								double ratePerSecond = RateLimiterProfile.getRatePerSecond(l);
								if (ratePerSecond < val)
									fastestRateMap.put(rateLimitType, ratePerSecond);
							}

							if (log.isInfoEnabled()) {
								log.info("Updating Binance " + RATE_LIMITER_TYPE_ORDERS + " rate limit to "
										+ fastestRateMap.get(RATE_LIMITER_TYPE_ORDERS) + "/sec");
								log.info("Updating Binance " + RATE_LIMITER_TYPE_REQUESTS + " rate limit to "
										+ fastestRateMap.get(RATE_LIMITER_TYPE_REQUESTS) + "/sec");
							}

							rateLimiterMap = fastestRateMap.entrySet().stream().collect(
									Collectors.toMap(Map.Entry::getKey, p -> RateLimiter.create(p.getValue())));
						}

					} catch (Exception e) {
						log.error("Couldn't process Binance exchange info update; trying again later", e);
					}

					try {
						Thread.sleep(params.getLong("BinanceExchangeInfoRefreshDelayMillis", 15 * 60 * 1000L));
					} catch (InterruptedException e1) {
						break;
					}
				}
			}
		});
		return jobs;
	}

}
