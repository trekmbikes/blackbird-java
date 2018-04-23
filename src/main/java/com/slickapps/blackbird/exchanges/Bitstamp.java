package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.exchanges.OperationType.QUERY_EXCHANGE_INFO;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_OPEN_ORDERS_FOR_CURRENCY_PAIR;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER_STATUS;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_TRADE_HISTORY;
import static java.math.MathContext.DECIMAL32;
import static org.knowm.xchange.bitstamp.dto.trade.BitstampOrderStatus.Finished;
import static org.knowm.xchange.dto.Order.OrderStatus.FILLED;
import static org.knowm.xchange.dto.Order.OrderStatus.NEW;
import static org.knowm.xchange.dto.Order.OrderStatus.PARTIALLY_FILLED;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.binance.dto.BinanceException;
import org.knowm.xchange.bitstamp.BitstampExchange;
import org.knowm.xchange.bitstamp.BitstampV2;
import org.knowm.xchange.bitstamp.dto.BitstampException;
import org.knowm.xchange.bitstamp.dto.trade.BitstampOrderStatusResponse;
import org.knowm.xchange.bitstamp.service.BitstampBaseService;
import org.knowm.xchange.bitstamp.service.BitstampTradeService;
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
import org.knowm.xchange.utils.nonce.AtomicLongCurrentTimeIncrementalNonceFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.tradingRules.TradingRule;
import com.slickapps.blackbird.service.ExchangeCalculationService.UserTradesAggregateResult;

import si.mazi.rescu.RestProxyFactory;
import si.mazi.rescu.SynchronizedValueFactory;

public class Bitstamp extends AbstractBlackbirdExchange {

	CustomBitstampTradeService customTradeService;
	CustomBitstampMetadataService customMetadataService;

	public Bitstamp(Parameters params) {
		initialize(params);
		if (!isEnabled())
			return;

		if (exchange != null) {
			customTradeService = new CustomBitstampTradeService(exchange);
			customMetadataService = new CustomBitstampMetadataService(exchange);
		}

		initializeLocal();
	}

	private void initializeLocal() {
		log.info("Querying dynamic Bitstamp configuration...");
		BitstampTradingPairInfo[] infos = callSyncWithRetry(() -> {
			BitstampTradingPairInfo[] i = customMetadataService.getExchangeInfo();
			return i;
		}, getRateLimitersForOperation(QUERY_EXCHANGE_INFO));

		for (BitstampTradingPairInfo info : infos) {
			TradingRule tradingRule = getOrCreateTradingRule(info.getCurrencyPair());
			tradingRule.setMinTotal(info.getMinimumOrderTotalPrice());
			tradingRule.setStepSizeForQuantity(new BigDecimal(10).pow(-1 * info.baseDecimals, DECIMAL32));
		}
	}

	public Exchange createExchangeInstance() {
		return new BitstampExchange() {
			@Override
			public TradeService getTradeService() {
				return customTradeService;
			}

			@Override
			protected void loadExchangeMetaData(InputStream is) {
				; // skip loading JSON metadata since we
			}

			@Override
			public void remoteInit() throws IOException, ExchangeException {
			}

//			@Override
//			public SynchronizedValueFactory<Long> getNonceFactory() {
//				return new AtomicLongCurrentTimeIncrementalNonceFactory() {
//					@Override
//					public Long createValue() {
//						Long val = super.createValue();
//						System.out.println("   >> " + val);
//						return val;
//					}
//				};
//			}
		};
	}

	public BitstampOrderStatusResponse queryOrderStatus(String orderId) throws IOException {
		try {
			return customTradeService.getBitstampOrder(Long.parseLong(orderId));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("orderId must be an integer");
		}
	}

	public UserTrades queryTradeHistory(CurrencyPair currencyPair) throws IOException {
		TradeHistoryParams tradeHistoryParams = customTradeService.createTradeHistoryParams();
		if (tradeHistoryParams instanceof TradeHistoryParamCurrencyPair && currencyPair != null) {
			((TradeHistoryParamCurrencyPair) tradeHistoryParams).setCurrencyPair(currencyPair);
		}
		return customTradeService.getTradeHistory(tradeHistoryParams);
	}

	@Override
	public boolean isExceptionRetryable(Exception e) {
		if (e instanceof BitstampException) {
			// TODO update this
			if (e.getMessage().toLowerCase().contains("service unavailable"))
				return true;
		}

		return false;
	}

	@Override
	public CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			try {
				OpenOrdersParams openOrdersParams = customTradeService.createOpenOrdersParams();
				boolean usingCurrencyPair = currencyPair != null
						&& openOrdersParams instanceof OpenOrdersParamCurrencyPair;
				if (usingCurrencyPair) {
					OpenOrdersParamCurrencyPair oopcp = (OpenOrdersParamCurrencyPair) openOrdersParams;
					oopcp.setCurrencyPair(currencyPair);
				}

				LimitOrder order = callSyncWithRetry(() -> {
					OpenOrders orders = customTradeService.getOpenOrders(openOrdersParams);
					for (LimitOrder lo : orders.getOpenOrders()) {
						if (lo.getId().equals(orderId))
							return lo;
					}
					return null;
				}, getRateLimitersForOperation(usingCurrencyPair ? QUERY_OPEN_ORDERS_FOR_CURRENCY_PAIR
						: QUERY_OPEN_ORDERS_FOR_ALL_CURRENCY_PAIRS));

				boolean foundOpenOrder = order != null;

				/*
				 * If it's in our OpenOrders, it's either NEW or PARTIALLY_FILLED. If it's not
				 * in our OpenOrders, we can't be sure if it was finished or some other closed
				 * status (cancelled, rejected, etc) - even if we have matching transactions
				 * below.
				 */
				OrderStatus orderStatus = null;
				if (foundOpenOrder) {
					orderStatus = NEW;
				} else {
					BitstampOrderStatusResponse orderStatusResponse = callSyncWithRetry(() -> {
						return queryOrderStatus(orderId);
					}, getRateLimitersForOperation(QUERY_ORDER_STATUS));
					if (orderStatusResponse != null && orderStatusResponse.getStatus() == Finished)
						orderStatus = FILLED;
				}

				/*
				 * Query all the trades and pull out the ones linked to the specified order. We
				 * can determine the orderType and other fields from these trades.
				 */
				UserTrades userTrades = callSyncWithRetry(() -> {
					return queryTradeHistory(currencyPair);
				}, getRateLimitersForOperation(QUERY_TRADE_HISTORY));

				UserTradesAggregateResult aggRes = calcService.analyzeTrades(currencyPair, orderId,
						userTrades.getUserTrades());

				if (aggRes.foundMatchingTrades && orderStatus == NEW)
					orderStatus = PARTIALLY_FILLED;

				if (!foundOpenOrder && aggRes.foundMatchingTrades) {
					/*
					 * We have to reconstruct what we know about the order based on the individual
					 * trades. We use the earliest trade timestamp for the order date (the best we
					 * can do). We can't determine the original limit amount.
					 */
					order = new LimitOrder(aggRes.orderType,
							orderStatus == FILLED ? aggRes.getSumTradeQuantitiesAfterFees(currencyPair) : null,
							currencyPair, orderId, aggRes.earliestDate, null, null, null, null, null);
				}

				if (order == null)
					return Optional.empty();

				order.setAveragePrice(aggRes.averagePrice);
				order.setCumulativeAmount(aggRes.sumTradeQuantities);
				order.setOrderStatus(orderStatus);
				order.setFee(aggRes.sumFees);
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

	static class CustomBitstampMetadataService extends BitstampBaseService {

		protected final CustomBitstampV2 customBitstamp;

		protected CustomBitstampMetadataService(Exchange exchange) {
			super(exchange);

			customBitstamp = RestProxyFactory.createProxy(CustomBitstampV2.class,
					exchange.getExchangeSpecification().getSslUri(), getClientConfig());
		}

		public BitstampTradingPairInfo[] getExchangeInfo() throws BinanceException, IOException {
			return customBitstamp.getTradingPairsInfo();
		}
	}

	static class CustomBitstampTradeService extends BitstampTradeService {

		protected CustomBitstampTradeService(Exchange exchange) {
			super(exchange);
		}

	}

	@Path("api/v2")
	static interface CustomBitstampV2 extends BitstampV2 {
		/**
		 * Get order status by Binance-assigned order ID
		 *
		 * @return
		 * @throws IOException
		 * @throws BinanceException
		 */
		@GET
		@Path("trading-pairs-info")
		BitstampTradingPairInfo[] getTradingPairsInfo() throws IOException, BinanceException;

	}

	public static class BitstampTradingPairInfo {
		public int baseDecimals;
		public String minimumOrder;
		public String name;
		public int counterDecimals;
		public String trading;
		public String urlSymbol;
		public String description;

		public BitstampTradingPairInfo(@JsonProperty("base_decimals") int baseDecimals,
				@JsonProperty("minimum_order") String minimumOrder, @JsonProperty("name") String name,
				@JsonProperty("counter_decimals") int counterDecimals, @JsonProperty("trading") String trading,
				@JsonProperty("url_symbol") String urlSymbol, @JsonProperty("description") String description) {
			this.baseDecimals = baseDecimals;
			this.minimumOrder = minimumOrder;
			this.name = name;
			this.counterDecimals = counterDecimals;
			this.trading = trading;
			this.urlSymbol = urlSymbol;
			this.description = description;
		}

		public CurrencyPair getCurrencyPair() {
			return new CurrencyPair(name);
		}

		public boolean isTradingEnabled() {
			return "Enabled".equalsIgnoreCase(trading);
		}

		public BigDecimal getMinimumOrderTotalPrice() {
			if (StringUtils.isNotBlank(minimumOrder)) {
				String[] tokens = minimumOrder.split("\\s");
				if (tokens.length == 2)
					try {
						return new BigDecimal(tokens[0]);
					} catch (NumberFormatException e) {
					}
			}
			return null;
		}

	}

}
