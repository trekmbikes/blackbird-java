package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_TRADE_HISTORY;
import static java.math.BigDecimal.ZERO;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.apache.commons.collections4.CollectionUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.hitbtc.v2.HitbtcAdapters;
import org.knowm.xchange.hitbtc.v2.HitbtcExchange;
import org.knowm.xchange.hitbtc.v2.dto.HitbtcException;
import org.knowm.xchange.hitbtc.v2.dto.HitbtcOrder;
import org.knowm.xchange.hitbtc.v2.dto.HitbtcOwnTrade;
import org.knowm.xchange.hitbtc.v2.service.HitbtcTradeService;
import org.knowm.xchange.service.trade.TradeService;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.util.MathUtil;

import si.mazi.rescu.ClientConfig;
import si.mazi.rescu.ClientConfigUtil;
import si.mazi.rescu.RestProxyFactory;

public class HitBTC extends AbstractBlackbirdExchange {

	private static final Map<String, OrderStatus> ORDER_STATUS_MAP = new HashMap<>();

	static {
		// not sure what to do with "suspended"
		ORDER_STATUS_MAP.put("new", OrderStatus.NEW);
		ORDER_STATUS_MAP.put("partiallyfilled", OrderStatus.PARTIALLY_FILLED);
		ORDER_STATUS_MAP.put("filled", OrderStatus.FILLED);
		ORDER_STATUS_MAP.put("canceled", OrderStatus.CANCELED);
		ORDER_STATUS_MAP.put("expired", OrderStatus.EXPIRED);
	}

	private static OrderStatus adaptHitbtcOrderStatus(String status) {
		return ORDER_STATUS_MAP.get(status.toLowerCase());
	}

	CustomHitBTCTradeService customTradeService;

	public HitBTC(Parameters params) {
		initialize(params);
		if (!isEnabled())
			return;

		if (exchange != null)
			customTradeService = new CustomHitBTCTradeService(exchange);
	}

	public Exchange createExchangeInstance() {
		return new HitbtcExchange() {
			@Override
			public TradeService getTradeService() {
				return customTradeService;
			}
		};
	}

	@Override
	public boolean isExceptionRetryable(Exception e) {
		if (e instanceof HitbtcException) {
			if (e.getMessage().toLowerCase().contains("service unavailable"))
				return true;
		}

		return false;
	}

	@Override
	protected LimitOrder createLimitOrder(OrderType orderType, CurrencyPair currencyPair, BigDecimal quantity,
			BigDecimal price) {
		String randomClientOrderId = UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
		return new CustomHitBTCLimitOrder(orderType, quantity, currencyPair, price, randomClientOrderId);
	}

	@Override
	public CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String clientOrderId) {
		return callAsyncWithRetry(() -> {
			try {
				HitbtcOrder o = callSyncWithRetry(() -> {
					return customTradeService.getActiveOrder(clientOrderId);
				}, getRateLimitersForOperation(QUERY_ORDER));

				if (o == null) {
					o = callSyncWithRetry(() -> {
						return customTradeService.getHistoricalOrder(clientOrderId);
					}, getRateLimitersForOperation(QUERY_ORDER));
				}
				if (o == null)
					return Optional.empty();

				CustomHitBTCOrder order = new CustomHitBTCOrder(o);

				if (order.getAveragePrice() == null || order.getAveragePrice().signum() == 0) {
					List<HitbtcOwnTrade> trades = callSyncWithRetry(() -> {
						return customTradeService.getTradesByOrder(order.getExchangeAssignedOrderId());
					}, getRateLimitersForOperation(QUERY_TRADE_HISTORY));

					if (CollectionUtils.isNotEmpty(trades)) {
						order.setAveragePrice(MathUtil.calculateWeightedAverage(
								trades.stream().map(p -> new BigDecimal[] { p.getQuantity(), p.getPrice() })
										.collect(Collectors.toList())));
						order.setFee(trades.stream().map(p -> p.getFee()).reduce(ZERO, BigDecimal::add));
					}
				}
				return Optional.of(order);
			} catch (ExchangeException e) {
				if (e.getMessage() != null && e.getMessage().contains("Invalid order"))
					return Optional.empty();
				throw e;
			}
		});
	}

	static class CustomHitBTCTradeService extends HitbtcTradeService {

		protected final CustomHitBTCAuthenticated customHitBTC;

		protected CustomHitBTCTradeService(Exchange exchange) {
			super(exchange);
			String apiKey = exchange.getExchangeSpecification().getApiKey();
			String secretKey = exchange.getExchangeSpecification().getSecretKey();

			ClientConfig config = getClientConfig();
			ClientConfigUtil.addBasicAuthCredentials(config, apiKey, secretKey);
			customHitBTC = RestProxyFactory.createProxy(CustomHitBTCAuthenticated.class,
					exchange.getExchangeSpecification().getSslUri(), config);
		}

		@Override
		public HitbtcOrder placeLimitOrderRaw(LimitOrder limitOrder) throws IOException {
			if (limitOrder instanceof CustomHitBTCLimitOrder == false)
				throw new IllegalArgumentException("Unsupported order type " + limitOrder.getClass().getName());
			CustomHitBTCLimitOrder o = (CustomHitBTCLimitOrder) limitOrder;

			String symbol = HitbtcAdapters.adaptCurrencyPair(o.getCurrencyPair());
			String side = HitbtcAdapters.getSide(o.getType()).toString();
			/*
			 * Override this to pass in the client order ID in the first param (stored in
			 * the ID field of the CustomHitBTCLimitOrder)
			 */
			return hitbtc.postHitbtcNewOrder(o.getId(), symbol, side, o.getLimitPrice(), o.getOriginalAmount(), "limit",
					"GTC");
		}

		public HitbtcOrder getHistoricalOrder(String clientOrderId) throws HitbtcException, IOException {
			List<HitbtcOrder> orders = customHitBTC.getHistoricalHitbtcOrder(clientOrderId);
			return CollectionUtils.isNotEmpty(orders) ? orders.get(0) : null;
		}

		public HitbtcOrder getActiveOrder(String clientOrderId) {
			HitbtcOrder order = null;
			try {
				order = customHitBTC.getActiveHitbtcOrder(clientOrderId);
			} catch (IOException | HitbtcException e) {
			}
			return order;
		}

		public List<HitbtcOwnTrade> getTradesByOrder(String orderId) {
			List<HitbtcOwnTrade> trades = null;
			try {
				trades = hitbtc.getHistorialTradesByOrder(orderId);
			} catch (IOException | HitbtcException e) {
				e.printStackTrace();
				// log.warn("Couldn't retrieve trades for order ID " + orderId, e);
			}
			return trades;
		}
	}

	@Path("/api/2/")
	static interface CustomHitBTCAuthenticated {
		/**
		 * Get historical order by client order ID
		 *
		 * @return
		 * @throws IOException
		 * @throws HitbtcException
		 */
		@GET
		@Path("history/order")
		List<HitbtcOrder> getHistoricalHitbtcOrder(@QueryParam("clientOrderId") String clientOrderId)
				throws IOException, HitbtcException;

		@GET
		@Path("order/{clientOrderId}")
		HitbtcOrder getActiveHitbtcOrder(@PathParam("clientOrderId") String clientOrderId)
				throws IOException, HitbtcException;
	}

	static class CustomHitBTCLimitOrder extends LimitOrder {
		private static final long serialVersionUID = -117530476154859754L;

		public CustomHitBTCLimitOrder(OrderType type, BigDecimal originalAmount, CurrencyPair currencyPair,
				BigDecimal limitPrice, String clientOrderId) {
			super(type, originalAmount, currencyPair, clientOrderId, null, limitPrice);
		}

	}

	static class CustomHitBTCOrder extends Order {
		private static final long serialVersionUID = 8016981886013853286L;

		private String exchangeAssignedOrderId;

		public CustomHitBTCOrder(HitbtcOrder hitbtcOrder) {
			super(HitbtcAdapters.adaptOrderType(hitbtcOrder.side), hitbtcOrder.quantity,
					HitbtcAdapters.adaptSymbol(hitbtcOrder.symbol), hitbtcOrder.clientOrderId,
					hitbtcOrder.getCreatedAt(), null /* average price */, hitbtcOrder.cumQuantity,
					null /* total sum of trade fees */, adaptHitbtcOrderStatus(hitbtcOrder.status));
			exchangeAssignedOrderId = hitbtcOrder.id;
		}

		public String getExchangeAssignedOrderId() {
			return exchangeAssignedOrderId;
		}
	}

}
