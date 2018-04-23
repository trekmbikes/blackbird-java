package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.exchanges.OperationType.QUERY_FOR_QUOTE;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER;
import static java.math.BigDecimal.ONE;
import static java.math.MathContext.DECIMAL32;
import static org.knowm.xchange.liqui.LiquiAdapters.adaptCurrencyPair;
import static org.knowm.xchange.liqui.LiquiAdapters.adaptTicker;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.marketdata.Trades;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.liqui.LiquiAdapters;
import org.knowm.xchange.liqui.LiquiExchange;
import org.knowm.xchange.liqui.dto.LiquiException;
import org.knowm.xchange.liqui.dto.marketdata.LiquiPairInfo;
import org.knowm.xchange.liqui.dto.marketdata.LiquiTicker;
import org.knowm.xchange.liqui.dto.trade.LiquiOrderInfo;
import org.knowm.xchange.liqui.dto.trade.LiquiTrade;
import org.knowm.xchange.liqui.dto.trade.LiquiUserTrade;
import org.knowm.xchange.liqui.service.LiquiMarketDataService;
import org.knowm.xchange.liqui.service.LiquiMarketDataServiceRaw;
import org.knowm.xchange.liqui.service.LiquiTradeService;
import org.knowm.xchange.liqui.service.LiquiTradeService.LiquiTradeHistoryParams;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.tradingRules.TradingRule;
import com.slickapps.blackbird.processes.QuoteGenerator;
import com.slickapps.blackbird.service.QuoteService;
import com.slickapps.blackbird.util.exception.ExchangeRuntimeException;

public class Liqui extends AbstractBlackbirdExchange {

	private static final BigDecimal LOWEST_ORDER_RATE = new BigDecimal(0.0001);
	private static final BigDecimal HIGHEST_ORDER_RATE = new BigDecimal(99999999);

	CustomLiquiTradeService customTradeService;

	public Liqui(Parameters params) {
		initialize(params);
		if (!isEnabled())
			return;

		if (exchange != null) {
			customTradeService = new CustomLiquiTradeService(exchange);
		}

		initializeLocal();
	}

	@Override
	protected boolean isOrderNotFoundException(Exception e) {
		return e.getMessage().contains("invalid order");
	}

	public Exchange createExchangeInstance() {
		return new LiquiExchange() {
			@Override
			public TradeService getTradeService() {
				return customTradeService;
			}
		};
	}

	private void initializeLocal() {
		log.info("Querying dynamic Liqui configuration...");

		LiquiMarketDataService marketDataService = (LiquiMarketDataService) exchange.getMarketDataService();
		Map<String, LiquiPairInfo> infoMap = marketDataService.getInfo();

		for (Entry<String, LiquiPairInfo> entry : infoMap.entrySet()) {
			CurrencyPair cp = LiquiAdapters.adaptCurrencyPair(entry.getKey());
			LiquiPairInfo info = entry.getValue();
			TradingRule tradingRule = getOrCreateTradingRule(cp);

			BigDecimal tickSize = ONE.scaleByPowerOfTen(-1 * info.getDecimalPlaces());
			tradingRule.setStepSizeForPrice(tickSize);

			if (info.getMinAmount() != null) {
				tradingRule.setMinQuantity(info.getMinAmount());
				tradingRule.setStepSizeForQuantity(info.getMinAmount());
			}
			if (info.getMinPrice() != null)
				tradingRule.setMinPrice(info.getMinPrice());
			if (info.getMinTotal() != null)
				tradingRule.setMinTotal(info.getMinTotal());
		}
	}

	@Override
	public QuoteGenerator createQuoteGenerator(QuoteService quoteService, EventListenerProvider eventListenerProvider) {
		return new LiquiQuoteGenerator(quoteService, this, eventListenerProvider);
	}

	@Override
	public CompletableFuture<List<Quote>> queryForQuotes(List<CurrencyPair> uniqueCurrencyPairs) {
		return callAsyncWithRetry(() -> {
			LiquiMarketDataServiceRaw r = (LiquiMarketDataServiceRaw) exchange.getMarketDataService();
			Map<String, LiquiTicker> ticker = r.getTicker(uniqueCurrencyPairs);
			return ticker.entrySet().stream()
					.map(entry -> adaptTicker(entry.getValue(), adaptCurrencyPair(entry.getKey())))
					.filter(p -> p.getBid() != null && p.getAsk() != null)
					.map(p -> new Quote(new ExchangeAndCurrencyPair(this, p.getCurrencyPair()), p.getBid(), p.getAsk()))
					.collect(Collectors.toList());
		}, getRateLimitersForOperation(QUERY_FOR_QUOTE));
	}

	/*
	 * Remove this when the API is changed to have LiquiTradeHistoryParams implement
	 * TradeHistoryParamCurrencyPair / Limit - CPB
	 */
	@Override
	protected TradeHistoryParams getTradeHistoryParams(CurrencyPair currencyPair, Integer count) {
		TradeService tradeService = exchange.getTradeService();
		LiquiTradeHistoryParams tradeHistoryParams = (LiquiTradeHistoryParams) tradeService.createTradeHistoryParams();
		if (currencyPair != null)
			tradeHistoryParams.setCurrencyPair(currencyPair);
		if (count != null)
			tradeHistoryParams.setAmount(count);
		return tradeHistoryParams;
	}

	class CustomLiquiTradeService extends LiquiTradeService {

		protected CustomLiquiTradeService(Exchange exchange) {
			super(exchange);
		}

		/* Patch for issue 2302 - CPB */
		@Override
		public UserTrades getTradeHistory(final TradeHistoryParams params) throws IOException {
			if (params instanceof LiquiTradeHistoryParams) {
				LiquiTradeHistoryParams lthp = (LiquiTradeHistoryParams) params;
				if (lthp.getCurrencyPair() == null) {
					return adaptTradesHistory(getTradeHistory());
				} else {
					return adaptTradesHistory(getTradeHistory(lthp.getCurrencyPair(), lthp.getAmount()));
				}
			}

			throw new LiquiException("Unable to get trade history with the provided params: " + params);
		}

		/* Next two methods patch for issue 2303 - CPB */
		public UserTrades adaptTradesHistory(final Map<Long, LiquiUserTrade> liquiTrades) {
			final List<UserTrade> trades = new ArrayList<>();
			for (final Map.Entry<Long, LiquiUserTrade> entry : liquiTrades.entrySet()) {
				trades.add(adaptTrade(entry.getValue(), entry.getKey()));
			}

			return new UserTrades(trades, Trades.TradeSortType.SortByID);
		}

		public UserTrade adaptTrade(final LiquiUserTrade liquiTrade, final Long tradeId) {
			OrderType orderType = LiquiAdapters.adaptOrderType(liquiTrade.getType());
			BigDecimal originalAmount = liquiTrade.getAmount();
			CurrencyPair pair = liquiTrade.getPair();
			Date timestamp = new Date((long) (liquiTrade.getTimestamp() * 1000L));
			BigDecimal price = liquiTrade.getRate();
			long orderId = liquiTrade.getOrderId();

			return new UserTrade(orderType, originalAmount, pair, price, timestamp, String.valueOf(tradeId),
					String.valueOf(orderId), new BigDecimal("0"), null);
		}

		/* Patch for bug with cumulative amount in 4.3.3 - CPB */
		@Override
		public Collection<Order> getOrder(final String... orderIds) throws IOException {
			List<Order> orders = new ArrayList<>();

			for (final String orderId : orderIds) {
				LiquiOrderInfo info = callSync(() -> {
					return getOrderInfo(Long.parseLong(orderId));
				}, getRateLimitersForOperation(QUERY_ORDER));
				if (info == null)
					continue;

				final OrderType orderType = LiquiAdapters.adaptOrderType(info.getType());
				final Date timestamp = new Date((long) (info.getTimestampCreated() * 1000L));
				LimitOrder order = new LimitOrder(orderType, info.getStartAmount(), info.getAmount(), info.getPair(),
						orderId, timestamp, info.getRate());

				BigDecimal filledAmount = order.getOriginalAmount().subtract(order.getCumulativeAmount());
				order.setCumulativeAmount(filledAmount);
				order.setOrderStatus(LiquiAdapters.adaptOrderStatus(info.getStatus()));
				orders.add(order);
			}

			return orders;
		}

		@Override
		public String placeLimitOrder(final LimitOrder limitOrder) throws IOException {
			LiquiTrade order = placeLiquiLimitOrder(limitOrder);
			return String.valueOf(order.getInitOrderId());
		}

		@Override
		public String placeMarketOrder(MarketOrder m) throws IOException {
			try {
				/* I figure this is better than just picking a huge and tiny number */
				Quote q = queryForQuote(m.getCurrencyPair()).get();
				BigDecimal two = new BigDecimal(2);
				LimitOrder lo = new LimitOrder(m.getType(), m.getOriginalAmount(), m.getCurrencyPair(), m.getId(),
						m.getTimestamp(),
						m.getType() == OrderType.ASK ? q.getBid().divide(two, DECIMAL32) : q.getAsk().multiply(two));
				return placeLimitOrder(lo);
			} catch (InterruptedException | ExecutionException e) {
				throw new ExchangeRuntimeException(Liqui.this, "Exception querying for quote", e);
			}
		}

	}

	class LiquiQuoteGenerator extends QuoteGenerator {

		public LiquiQuoteGenerator(QuoteService quoteService, BlackbirdExchange exchange,
				EventListenerProvider eventListenerProvider) {
			super(quoteService, exchange, eventListenerProvider);
		}

		protected Collection<Quote> getQuotes() throws InterruptedException, ExecutionException {
			return exchange.queryForQuotes(uniqueCurrencyPairs).get();
		}

	}

}
