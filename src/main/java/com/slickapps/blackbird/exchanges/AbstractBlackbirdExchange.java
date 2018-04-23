package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.Main._100;
import static com.slickapps.blackbird.exchanges.OperationType.CANCEL_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.PLACE_LIMIT_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.PLACE_MARKET_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_EXCHANGE_INFO;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_ORDER_BOOK;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_TRADE_HISTORY;
import static com.slickapps.blackbird.exchanges.OperationType.QUERY_WALLET;
import static com.slickapps.blackbird.model.orderCompletion.OrderRollbackType.CUMULATIVE;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static com.slickapps.blackbird.util.FormatUtil.getQuantityFormatter;
import static com.slickapps.blackbird.util.exception.ExceptionUtil.wrapExceptionHandling;
import static java.lang.Integer.parseInt;
import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.ZERO;
import static java.math.MathContext.DECIMAL64;
import static java.time.LocalDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toCollection;
import static org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords;
import static org.knowm.xchange.dto.Order.OrderType.ASK;
import static org.knowm.xchange.dto.Order.OrderType.BID;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencyPair;
import org.knowm.xchange.service.trade.params.TradeHistoryParamLimit;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.RateLimiter;
import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.model.DummyOrder;
import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.OrderPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderRollbackType;
import com.slickapps.blackbird.model.tradingRules.TradingRule;
import com.slickapps.blackbird.processes.QuoteGenerator;
import com.slickapps.blackbird.service.ExchangeCalculationService;
import com.slickapps.blackbird.service.ExchangeCalculationService.UserTradesAggregateResult;
import com.slickapps.blackbird.service.QuoteService;
import com.slickapps.blackbird.util.NoOpExchange;
import com.slickapps.blackbird.util.RateLimitedSupplier;
import com.slickapps.blackbird.util.RateLimiterProfile;
import com.slickapps.blackbird.util.exception.CommunicationExceptionRetrySupplier;
import com.slickapps.blackbird.util.exception.SupplierWithException;

/**
 * @author Connor
 *
 */
public abstract class AbstractBlackbirdExchange implements BlackbirdExchange {

	/*
	 * If not configured in the properties and no dynamic update is being performed
	 * by the exchange, default to the safe rate of 1 every 2 seconds
	 */
	private static final String DEFAULT_RATE_LIMITER_NAME = "_DEFAULT";
	public static final double DEFAULT_MAX_REQUESTS_PER_SEC = 0.5;

	private static final int RECOVERY_DURATION_MINUTES = 1;
	/* Only refresh wallet at most once every 5 minutes */
	private static final int WALLET_CACHE_EXPIRY_SECONDS = 5 * 60;
	private static final int CANCEL_OR_REVERT_ORDER_INITIAL_DELAY_MILLIS = 30000;

	private static final String DUMMY_ORDER_ID_PREFIX = "DummyOrder";
	protected static final BigDecimal DUMMY_MARKET_ORDER_PRICE = new BigDecimal(100);
	protected static final int DEFAULT_RETRY_COUNT = 3;

	/* Unit test support - not ideal to put here but TODO */
	protected static AtomicInteger dummyOrderCounter = new AtomicInteger(1);
	protected static Map<String, DummyOrder> dummyOrderMap = new ConcurrentHashMap<>();

	protected Logger log;

	protected Parameters params;
	protected String propertyPrefix;
	protected Exchange exchange;
	protected boolean enabled;

	protected String apiKey;
	protected String apiSecret;
	protected String apiUserId;
	protected BigDecimal feePercentage;

	protected int maxRequestsPerTenMinutes;
	protected int uniqueId;

	/*
	 * May be empty
	 */
	protected List<CurrencyPair> currenciesForShortPositions;
	protected List<CurrencyPair> currenciesForLongPositions;

	protected Map<Currency, TradingRule> tradingRulesByCurrency = new HashMap<>();
	protected Map<CurrencyPair, TradingRule> tradingRulesByCurrencyPair = new HashMap<>();

	protected Wallet walletCache;
	protected LocalDateTime walletLastUpdated;
	protected String walletName;

	protected LocalDateTime disabledUntilDate;
	protected ExchangeCalculationService calcService = new ExchangeCalculationService();
	protected Map<String, RateLimiter> rateLimiterMap = new HashMap<>();
	private ExecutorService executorService = Executors.newCachedThreadPool();

	protected AbstractBlackbirdExchange() {
		this.log = LoggerFactory.getLogger(getClass());
	}

	protected void initialize(Parameters params) {
		this.params = params;

		// add underscore or something if needed
		this.propertyPrefix = getName();

		this.maxRequestsPerTenMinutes = params.getInteger(propertyPrefix + "10mRateLimit", 0);
		rateLimiterMap.put(DEFAULT_RATE_LIMITER_NAME,
				RateLimiter.create(this.maxRequestsPerTenMinutes != 0 ? this.maxRequestsPerTenMinutes / 600.0
						: DEFAULT_MAX_REQUESTS_PER_SEC));
		
		Boolean enabled = params.getBoolean(propertyPrefix + "Enabled", false);

		if (enabled == null || !enabled) {
			this.enabled = false;
			exchange = new NoOpExchange();
			log.debug("Skipping addition of exchange {} because it's not enabled", getName());
			return;
		}

		log.info("Initializing exchange {}...", getName());
		this.enabled = true;


		// user ID optional but I think everyone should have an API, secret and fees
		ensureFieldsProvided(propertyPrefix + "ApiKey", propertyPrefix + "SecretKey", propertyPrefix + "FeePercentage");

		this.currenciesForShortPositions = calcService
				.parseCurrencyPairsCSV(params.getString(propertyPrefix + "CurrencyPairsShortable", ""));
		this.currenciesForLongPositions = calcService
				.parseCurrencyPairsCSV(params.getString(propertyPrefix + "CurrencyPairs", ""));

		params.getAllParams().forEach((key, val) -> {
			String propName = (String) key;
			String propVal = (String) val;
			String prefix = null;

			prefix = propertyPrefix + "MinimumOrderQuantity";
			if (propName.startsWith(prefix))
				getOrCreateTradingRule(propName.substring(prefix.length())).setMinQuantity(new BigDecimal(propVal));

			prefix = propertyPrefix + "MinimumOrderPrice";
			if (propName.startsWith(prefix))
				getOrCreateTradingRule(propName.substring(prefix.length())).setMinPrice(new BigDecimal(propVal));

			prefix = propertyPrefix + "MinimumOrderTotal";
			if (propName.startsWith(prefix))
				getOrCreateTradingRule(propName.substring(prefix.length())).setMinTotal(new BigDecimal(propVal));

			prefix = propertyPrefix + "StepSize";
			if (propName.startsWith(prefix))
				getOrCreateTradingRule(propName.substring(prefix.length()))
						.setStepSizeForQuantity(new BigDecimal(propVal));

			prefix = propertyPrefix + "LeveragesSupported";
			if (propName.startsWith(prefix)) {
				SortedSet<Integer> leverages = Arrays.stream(propVal.split("\\s*,\\s*")).map(p -> parseInt(p))
						.collect(toCollection(TreeSet::new));
				getOrCreateTradingRule(propName.substring(prefix.length())).setLeveragesSupported(leverages);
			}
		});

		this.apiKey = params.getString(propertyPrefix + "ApiKey", null);
		this.apiSecret = params.getString(propertyPrefix + "SecretKey", null);
		this.apiUserId = params.getString(propertyPrefix + "UserId", null);
		this.feePercentage = params.getBigDecimal(propertyPrefix + "FeePercentage", new BigDecimal(100)).divide(_100,
				DECIMAL64);
		this.walletName = params.getString(propertyPrefix + "WalletName", null);

		exchange = createExchange();
	}

	protected TradingRule getOrCreateTradingRule(String currencyOrPairCode) {
		TradingRule tradingRule = null;
		if (currencyOrPairCode.contains("_")) {
			String[] currencyTokens = currencyOrPairCode.split("_");
			tradingRule = getOrCreateTradingRule(new CurrencyPair(currencyTokens[0], currencyTokens[1]));
		} else {
			tradingRule = getOrCreateTradingRule(Currency.getInstance(currencyOrPairCode));
		}
		return tradingRule;
	}

	protected TradingRule getOrCreateTradingRule(CurrencyPair cp) {
		return tradingRulesByCurrencyPair.computeIfAbsent(cp, k -> new TradingRule());
	}

	protected TradingRule getOrCreateTradingRule(Currency c) {
		return tradingRulesByCurrency.computeIfAbsent(c, k -> new TradingRule());
	}

	protected Exchange createExchange() {
		return callSyncWithRetry(() -> {
			Exchange exchange = createExchangeInstance();
			exchange.applySpecification(getExchangeSpec());
			return exchange;
		}, getRateLimitersForOperation(QUERY_EXCHANGE_INFO));
	}

	public String getName() {
		return getClass().getSimpleName();
	}

	public String getDbTableName() {
		return getClass().getSimpleName().toLowerCase();
	}

	protected abstract Exchange createExchangeInstance();

	/**
	 * This method requests that the Exchange provided by getExchange() create a
	 * default ExchangeSpecification and sets the userName, apiKey and secretKey in
	 * this spec. Override or extend as needed; if null is returned, no attempt is
	 * made to initialize an org.knowm.xchange.Exchange for use in the query* or
	 * send*Order methods and these should also be overridden.
	 */
	protected ExchangeSpecification getExchangeSpec() throws ExchangeException {
		ExchangeSpecification exSpec = createExchangeInstance().getDefaultExchangeSpecification();
		exSpec.setUserName(apiUserId);
		exSpec.setApiKey(apiKey);
		exSpec.setSecretKey(apiSecret);
		return exSpec;
	}

	/**
	 * @return A union of unique CurrencyPairs for both short and long positions
	 */
	public Set<CurrencyPair> getCombinedCurrencyPairs() {
		Set<CurrencyPair> uniqueCurrencyPairs = new LinkedHashSet<>(getCurrencyPairsForShortPositions());
		uniqueCurrencyPairs.addAll(getCurrencyPairsForLongPositions());
		return uniqueCurrencyPairs;
	}

	private BigDecimal applyTradingRules(CurrencyPair c, Function<TradingRule, BigDecimal> f, BigDecimal defaultVal) {
		TradingRule tr1 = tradingRulesByCurrencyPair.get(c);
		TradingRule tr2 = tradingRulesByCurrency.get(c.base);

		if (tr1 != null) {
			BigDecimal result = f.apply(tr1);
			if (result != null)
				return result;
		}

		if (tr2 != null) {
			BigDecimal result = f.apply(tr2);
			if (result != null)
				return result;
		}

		return defaultVal;
	}

	@Override
	public BigDecimal getOrderMinQuantity(CurrencyPair c) {
		return applyTradingRules(c, tr -> tr.getMinQuantity(), ZERO);
	}

	@Override
	public BigDecimal getOrderMinPrice(CurrencyPair c) {
		return applyTradingRules(c, tr -> tr.getMinPrice(), ZERO);
	}

	@Override
	public BigDecimal getOrderMinTotal(CurrencyPair c) {
		return applyTradingRules(c, tr -> tr.getMinTotal(), ZERO);
	}

	@Override
	public BigDecimal getOrderQuantityStepSize(CurrencyPair c) {
		return applyTradingRules(c, tr -> tr.getStepSizeForQuantity(), null);
	}

	@Override
	public BigDecimal getOrderPriceStepSize(CurrencyPair c) {
		return applyTradingRules(c, tr -> tr.getStepSizeForPrice(), null);
	}

	public SortedSet<Integer> getLeveragesSupported(CurrencyPair c) {
		TradingRule tr = tradingRulesByCurrencyPair.get(c);
		if (tr != null && !tr.getLeveragesSupported().isEmpty())
			return tr.getLeveragesSupported();

		tr = tradingRulesByCurrency.get(c.base);
		if (tr != null && !tr.getLeveragesSupported().isEmpty())
			return tr.getLeveragesSupported();

		return new TreeSet<>();
	}

	protected void ensureFieldsProvided(String... paramsProperties) {
		for (String prop : paramsProperties) {
			if (!params.isSpecified(prop))
				throw new RuntimeException("Property \"" + prop + "\" was not specified.");
		}
	}

	protected String placeDummyOrder(BigDecimal quantity, BigDecimal price, OrderType orderType) {
		String orderId = DUMMY_ORDER_ID_PREFIX + dummyOrderCounter.getAndIncrement();
		dummyOrderMap.put(orderId, new DummyOrder(quantity, price, orderType));
		return orderId;
	}

	@Override
	public CompletableFuture<Quote> queryForQuote(CurrencyPair currencyPair) {
		BlackbirdExchange e = this;

		return callAsyncWithRetry(() -> {
			MarketDataService marketDataService = exchange.getMarketDataService();
			Ticker ticker = marketDataService.getTicker(currencyPair);
			if (ticker.getBid() == null || ticker.getAsk() == null) {
				log.warn("Exchange {} returned a null bid or ask, ignoring result...", exchange);
				throw new Exception("Null bid/ask returned by exchange, ignoring result");
			}
			return new Quote(new ExchangeAndCurrencyPair(e, currencyPair), ticker.getBid(), ticker.getAsk());
		}, getRateLimitersForOperation(OperationType.QUERY_FOR_QUOTE));
	}

	@Override
	public CompletableFuture<List<Quote>> queryForQuotes(List<CurrencyPair> uniqueCurrencyPairs) {
		List<CompletableFuture<Quote>> allFutures = new ArrayList<>();
		for (CurrencyPair cp : uniqueCurrencyPairs)
			allFutures.add(queryForQuote(cp));

		return CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[allFutures.size()]))
				.thenApply(v -> allFutures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
	}

	@Override
	public CompletableFuture<Wallet> queryWallet(boolean allowCache) {
		if (!allowCache || walletCache == null || walletLastUpdated == null
				|| SECONDS.between(walletLastUpdated, now()) > WALLET_CACHE_EXPIRY_SECONDS) {
			return callAsyncWithRetry(getWalletSupplier(), getRateLimitersForOperation(QUERY_WALLET));
		} else {
			/* getBalance() never returns null, but zero */
			return completedFuture(walletCache);
		}
	}

	@Override
	public CompletableFuture<Balance> queryBalance(Currency currency, boolean allowCache) {
		CompletableFuture<Wallet> walletFuture = queryWallet(allowCache);
		return walletFuture.thenApply(p -> {
			return p.getBalance(currency);
		});
	}

	@Override
	public CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			return queryOrderWithinRate(currencyPair, orderId);
		}, getRateLimitersForOperation(QUERY_ORDER));
	}

	@Override
	public CompletableFuture<Optional<OrderStatus>> queryOrderStatus(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			Optional<Order> opt = queryOrderWithoutAggregatesWithinRate(currencyPair, orderId);
			if (!opt.isPresent())
				return Optional.empty();

			Order o = opt.get();
			return Optional.ofNullable(o.getStatus());
		});
	}

	protected Optional<Order> queryOrderWithinRate(CurrencyPair currencyPair, String orderId) throws Exception {
		Optional<Order> opt = queryOrderWithoutAggregatesWithinRate(currencyPair, orderId);
		if (!opt.isPresent())
			return Optional.empty();
		Order o = opt.get();

		UserTrades userTrades = queryTradeHistoryWithinRate(currencyPair);
		UserTradesAggregateResult aggregateResult = calcService.analyzeTrades(currencyPair, orderId,
				userTrades.getUserTrades());
		o.setAveragePrice(aggregateResult.averagePrice);
		o.setCumulativeAmount(aggregateResult.sumTradeQuantities);
		o.setFee(aggregateResult.sumFees);

		return Optional.of(o);
	}

	protected Optional<Order> queryOrderWithoutAggregatesWithinRate(CurrencyPair currencyPair, String orderId)
			throws Exception {
		TradeService tradeService = exchange.getTradeService();
		Collection<Order> orders = null;
		try {
			orders = tradeService.getOrder(orderId);
		} catch (Exception e) {
			if (!isOrderNotFoundException(e))
				throw e;
		}

		if (CollectionUtils.isEmpty(orders))
			return Optional.empty();

		return Optional.of(orders.iterator().next());
	}

	public UserTrades queryTradeHistoryWithinRate(CurrencyPair currencyPair) {
		TradeService tradeService = exchange.getTradeService();
		TradeHistoryParams tradeHistoryParams = getTradeHistoryParams(currencyPair, null);

		UserTrades userTrades = callSyncWithRetry(() -> {
			UserTrades ut = tradeService.getTradeHistory(tradeHistoryParams);
			return ut;
		}, getRateLimitersForOperation(QUERY_TRADE_HISTORY));
		return userTrades;
	}

	protected TradeHistoryParams getTradeHistoryParams(CurrencyPair currencyPair, Integer count) {
		TradeService tradeService = exchange.getTradeService();
		TradeHistoryParams tradeHistoryParams = tradeService.createTradeHistoryParams();
		if (currencyPair != null && tradeHistoryParams instanceof TradeHistoryParamCurrencyPair)
			((TradeHistoryParamCurrencyPair) tradeHistoryParams).setCurrencyPair(currencyPair);
		if (count != null && tradeHistoryParams instanceof TradeHistoryParamLimit)
			((TradeHistoryParamLimit) tradeHistoryParams).setLimit(count);
		return tradeHistoryParams;
	}

	protected boolean isOrderNotFoundException(Exception e) {
		return false;
	}

	protected SupplierWithException<Wallet> getWalletSupplier() {
		return () -> {
			AccountService accountService = exchange.getAccountService();
			AccountInfo accountInfo = accountService.getAccountInfo();
			Map<String, Wallet> wallets = accountInfo.getWallets();
			Wallet wallet = null;
			if (wallets.isEmpty()) {
				throw new UnsupportedOperationException(
						"The exchange " + getClass().getSimpleName() + " did not return any wallets.");
			} else if (wallets.size() > 1) {
				if (StringUtils.isBlank(walletName)) {
					throw new UnsupportedOperationException("The exchange " + getClass().getSimpleName()
							+ " supports multiple wallets: {" + StringUtils.join(wallets.keySet(), ",")
							+ "}; no wallet name was specified in blackbird.conf.");
				}

				wallet = wallets.get(walletName);
				if (wallet == null) {
					throw new UnsupportedOperationException("The exchange " + getClass().getSimpleName()
							+ " does not have a wallet with name '" + walletName + "'");
				}
			} else {
				/*
				 * Even if we have only one wallet, if we specified a walletName and the wallet
				 * returned doesn't have this name, stop so we don't mess with the wrong
				 * account.
				 */
				if (StringUtils.isNotBlank(walletName) && wallets.get(walletName) == null)
					throw new UnsupportedOperationException("The exchange " + getClass().getSimpleName()
							+ " does not have a wallet with name '" + walletName + "'");

				wallet = wallets.values().iterator().next();
			}

			walletCache = wallet;
			walletLastUpdated = LocalDateTime.now();
			return wallet;
		};
	}

	@Override
	public CompletableFuture<String> openLongPosition(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		return useMarketOrder ? sendMarketOrder(BID, quantity, currencyPair)
				: sendLimitOrder(BID, currencyPair, quantity, useMarketOrder, limitPriceOverride);
	}

	@Override
	public CompletableFuture<String> closeLongPosition(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		return useMarketOrder ? sendMarketOrder(ASK, quantity, currencyPair)
				: sendLimitOrder(ASK, currencyPair, quantity, useMarketOrder, limitPriceOverride);
	}

	public boolean isShortable() {
		return !currenciesForShortPositions.isEmpty();
	}

	@Override
	public final CompletableFuture<String> openShortPosition(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		if (isShortable())
			return openShortPositionImp(currencyPair, quantity, useMarketOrder, limitPriceOverride);
		throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support short orders");
	}

	/*
	 * Expected that this will be overridden by exchanges that support short orders
	 */
	protected CompletableFuture<String> openShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		return null;
	}

	@Override
	public final CompletableFuture<String> closeShortPosition(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		if (isShortable())
			return closeShortPositionImp(currencyPair, quantity, useMarketOrder, limitPriceOverride);
		throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support short orders");
	}

	/*
	 * Expected that this will be overridden by exchanges that support short orders
	 */
	protected CompletableFuture<String> closeShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		return null;
	}

	protected CompletableFuture<String> sendLimitOrder(OrderType orderType, CurrencyPair currencyPair,
			BigDecimal quantity, boolean useMarketOrder, BigDecimal limitPriceOverride) {
		BigDecimal limitPrice = limitPriceOverride;
		if (!useMarketOrder && limitPriceOverride == null)
			limitPrice = callSyncWithRetry(queryLimitPriceInternal(currencyPair, quantity, orderType),
					getRateLimitersForOperation(QUERY_ORDER_BOOK));
		BigDecimal finalPrice = limitPrice;

		return callAsyncWithRetry(() -> {
			log.info("Trying to send a \"{}\" limit order on {}: {}@{}...", orderType, getName(),
					getQuantityFormatter().format(quantity), formatCurrency(currencyPair.counter, finalPrice));

			String orderId = null;
			if (params.demoMode) {
				orderId = placeDummyOrder(quantity, finalPrice, orderType);
			} else {
				TradeService tradeService = exchange.getTradeService();
				LimitOrder limitOrder = createLimitOrder(orderType, currencyPair, quantity, finalPrice);
				orderId = tradeService.placeLimitOrder(limitOrder);
			}
			log.info("Done placing {} limit order on {} (order ID: {}, {}@{})", currencyPair, getName(), orderId,
					getQuantityFormatter().format(quantity), formatCurrency(currencyPair.counter, finalPrice));
			return orderId;
		}, getRateLimitersForOperation(PLACE_LIMIT_ORDER));
	}

	protected LimitOrder createLimitOrder(OrderType orderType, CurrencyPair currencyPair, BigDecimal quantity,
			BigDecimal price) {
		return new LimitOrder(orderType, quantity, currencyPair, null, null, price);
	}

	protected CompletableFuture<String> sendMarketOrder(OrderType orderType, BigDecimal quantity,
			CurrencyPair currencyPair) {
		return callAsyncWithRetry(() -> {
			log.info("Trying to send a \"{}\" market order on {}: {}...", orderType, getName(),
					getQuantityFormatter().format(quantity));

			String orderId = null;
			if (params.demoMode) {
				orderId = placeDummyOrder(quantity, DUMMY_MARKET_ORDER_PRICE, orderType);
			} else {
				TradeService tradeService = exchange.getTradeService();
				MarketOrder marketOrder = createMarketOrder(orderType, quantity, currencyPair);
				orderId = tradeService.placeMarketOrder(marketOrder);
			}
			log.info("Done placing {} market order on {} (order ID: {}, qty {})", currencyPair, getName(), orderId,
					getQuantityFormatter().format(quantity));
			return orderId;
		}, getRateLimitersForOperation(PLACE_MARKET_ORDER));
	}

	protected MarketOrder createMarketOrder(OrderType orderType, BigDecimal quantity, CurrencyPair currencyPair) {
		return new MarketOrder(orderType, quantity, currencyPair);
	}

	@Override
	public CompletableFuture<Boolean> cancelOrder(CurrencyPair currencyPair, String orderId) {
		return callAsyncWithRetry(() -> {
			if (log.isInfoEnabled())
				log.info("Trying to cancel the {} order with ID {}...", getName(), orderId);

			TradeService tradeService = exchange.getTradeService();
			return tradeService.cancelOrder(orderId);
		}, getRateLimitersForOperation(CANCEL_ORDER));
	}

	@Override
	public CompletableFuture<BigDecimal> queryLimitPrice(CurrencyPair currencyPair, BigDecimal volume,
			OrderType orderType) {
		return callAsyncWithRetry(queryLimitPriceInternal(currencyPair, volume, orderType),
				getRateLimitersForOperation(QUERY_ORDER_BOOK));
	}

	protected SupplierWithException<BigDecimal> queryLimitPriceInternal(CurrencyPair currencyPair, BigDecimal volume,
			OrderType orderType) {
		return () -> {
			MarketDataService marketDataService = exchange.getMarketDataService();
			OrderBook orderBook = marketDataService.getOrderBook(currencyPair);
			List<LimitOrder> bidsOrAsks = orderBook.getOrders(orderType);

			NumberFormat quantityFormatter = getQuantityFormatter();
			BigDecimal volTimesOrderBookFactor = volume.abs().multiply(params.orderBookFactor);

			if (log.isInfoEnabled())
				log.info("Looking for a limit price to fill {} (including order book factor padding) on {}...",
						formatCurrency(currencyPair.base, volTimesOrderBookFactor), getName());

			BigDecimal price = ZERO;
			BigDecimal tmpVol = ZERO;

			for (LimitOrder o : bidsOrAsks) {
				price = o.getLimitPrice();
				BigDecimal amount = o.getOriginalAmount();
				if (log.isInfoEnabled())
					log.info("order book: {} @ {}", quantityFormatter.format(amount),
							formatCurrency(currencyPair.counter, price));
				tmpVol = tmpVol.add(amount);
				if (tmpVol.compareTo(volTimesOrderBookFactor) != -1)
					break;
			}

			return price;
		};
	}

	@Override
	public CompletableFuture<OrderPair> cancelOrRevertLongOrder(CurrencyPair currencyPair, String longOrderId,
			OrderRollbackType rollbackType) {
		log.info("Cancelling {} order ID {} on exchange {} after a delay of {}...", currencyPair, longOrderId,
				getName(), formatDurationWords(CANCEL_OR_REVERT_ORDER_INITIAL_DELAY_MILLIS, true, true));

		return callAsync(() -> {
			Thread.sleep(CANCEL_OR_REVERT_ORDER_INITIAL_DELAY_MILLIS);

			try {
				cancelOrder(currencyPair, longOrderId).get();
			} catch (Exception e) {
				log.warn("Couldn't cancel " + currencyPair + " long order ID " + longOrderId + " at " + getName()
						+ " (possibly because it was already filled); attempting to revert any " + rollbackType
						+ " amount.");
				log.debug("Full cancellation failure reason:", e);
			}

			Optional<Order> orderOpt = queryOrder(currencyPair, longOrderId).get();
			if (!orderOpt.isPresent())
				return new OrderPair(null, null);

			Order order = orderOpt.get();
			BigDecimal amountToRevert = rollbackType == CUMULATIVE ? order.getCumulativeAmount()
					: order.getRemainingAmount();
			if (amountToRevert.signum() == 1) {
				log.info("Attempting to revert long order on " + getName() + " with a " + rollbackType
						+ " filled quantity of {}...", formatCurrency(currencyPair.base, amountToRevert));
				String orderId = closeLongPosition(currencyPair, amountToRevert, true, null).get();
				Optional<Order> revertOrder = queryOrder(currencyPair, orderId).get();
				log.info("{} long market order placed.", currencyPair);
				return new OrderPair(order, revertOrder.orElse(null));
			} else {
				log.info("No amount of {} to revert was necessary for long order ID {} on {}.", currencyPair,
						longOrderId, getName());
			}

			return new OrderPair(order, null);
		});
	}

	@Override
	public CompletableFuture<OrderPair> cancelOrRevertShortOrder(CurrencyPair currencyPair, String shortOrderId,
			OrderRollbackType rollbackType) {
		return callAsync(() -> {
			Thread.sleep(30000);
			try {
				cancelOrder(currencyPair, shortOrderId).get();
			} catch (Exception e) {
				log.warn("Couldn't cancel " + currencyPair + " short order ID " + shortOrderId + " at " + getName()
						+ " (possibly because it was already filled); attempting to reverse any " + rollbackType
						+ " amount...");
				log.debug("Full cancellation failure reason:", e);
			}

			Optional<Order> orderOpt = queryOrder(currencyPair, shortOrderId).get();
			if (!orderOpt.isPresent())
				return new OrderPair(null, null);

			Order order = orderOpt.get();
			BigDecimal amountToRevert = rollbackType == OrderRollbackType.CUMULATIVE ? order.getCumulativeAmount()
					: order.getRemainingAmount();
			if (amountToRevert.signum() == 1) {
				log.info(
						"Attempting to revert " + currencyPair + " short order on " + getName() + " with a "
								+ rollbackType + " filled quantity of {}...",
						formatCurrency(currencyPair.base, amountToRevert));
				String orderId = closeShortPosition(currencyPair, amountToRevert, true, null).get();
				Optional<Order> revertOrder = queryOrder(currencyPair, orderId).get();
				log.info("{} short market order placed on {}.", currencyPair, getName());
				return new OrderPair(order, revertOrder.orElse(null));
			} else {
				log.info("No amount of {} to revert was necessary on {} for short order ID {}.", currencyPair,
						getName(), shortOrderId);
			}

			return new OrderPair(order, null);
		});
	}

	protected Integer getMaxLeverage(CurrencyPair c) {
		SortedSet<Integer> leverages = getLeveragesSupported(c);
		if (CollectionUtils.isNotEmpty(leverages))
			return leverages.last();
		return null;
	}

	public BigDecimal getMaxLeveragableAmount(CurrencyPair c, BigDecimal balance) {
		Integer leverage = getMaxLeverage(c);
		if (leverage != null)
			return balance.multiply(new BigDecimal(leverage));
		return balance;
	}

	public BigDecimal getExposureWithMaxLeverage(CurrencyPair c, BigDecimal baseAmount) {
		Integer leverage = getMaxLeverage(c);
		if (leverage != null)
			return baseAmount.divide(new BigDecimal(leverage), MathContext.DECIMAL64);
		return baseAmount;
	}

	@Override
	public BigDecimal roundQuantityToStepSizeIfNecessary(boolean roundDown, BigDecimal volume,
			CurrencyPair currencyPair) {
		BigDecimal stepSize = getOrderQuantityStepSize(currencyPair);
		if (stepSize != null) {
			// returns zero if not defined
			BigDecimal minAmount = getOrderMinQuantity(currencyPair);
			BigDecimal diff = volume.subtract(minAmount);
			BigDecimal[] quotientAndRemainder = diff.divideAndRemainder(stepSize, MathContext.DECIMAL64);
			if (quotientAndRemainder[1].signum() == 1)
				volume = minAmount.add(quotientAndRemainder[0].add(roundDown ? ZERO : ONE).multiply(stepSize))
						.stripTrailingZeros();
		}
		return volume;
	}

	@Override
	public BigDecimal roundPriceToStepSizeIfNecessary(boolean roundDown, BigDecimal price, CurrencyPair currencyPair) {
		BigDecimal stepSize = getOrderPriceStepSize(currencyPair);
		if (stepSize != null) {
			// returns zero if not defined
			BigDecimal minPrice = getOrderMinPrice(currencyPair);
			BigDecimal diff = price.subtract(minPrice);
			BigDecimal[] quotientAndRemainder = diff.divideAndRemainder(stepSize, MathContext.DECIMAL64);
			if (quotientAndRemainder[1].signum() == 1)
				price = minPrice.add(quotientAndRemainder[0].add(roundDown ? ZERO : ONE).multiply(stepSize))
						.stripTrailingZeros();
		}
		return price;
	}

	@Override
	public boolean isExceptionRetryable(Exception e) {
		return false;
	}

	public boolean isDisabledTemporarily() {
		return disabledUntilDate != null && LocalDateTime.now().isBefore(disabledUntilDate);
	}

	public void disableTemporarily() {
		disabledUntilDate = LocalDateTime.now().plusMinutes(RECOVERY_DURATION_MINUTES);
	}

	public boolean isDisabledTemporarilyOrNeedsWalletPopulation() {
		return isDisabledTemporarily() || !isWalletPopulated();
	}

	public boolean isWalletPopulated() {
		return walletLastUpdated != null;
	}

	@Override
	public QuoteGenerator createQuoteGenerator(QuoteService quoteService, EventListenerProvider eventListenerProvider) {
		return new QuoteGenerator(quoteService, this, eventListenerProvider);
	}

	public void clearWallet() {
		walletLastUpdated = null;
		walletCache = null;
	}

	// ---- Task execution methods

	public RateLimiter getDefaultRateLimiter() {
		return rateLimiterMap.get(DEFAULT_RATE_LIMITER_NAME);
	}

	public RateLimiter getOrDefaultRateLimiter(String rateLimiterName) {
		RateLimiter rateLimiter = rateLimiterMap.get(rateLimiterName);
		return rateLimiter == null ? getDefaultRateLimiter() : rateLimiter;
	}

	protected RateLimiterProfile[] getRateLimitersForOperation(OperationType type, Object... operationMethodArgs) {
		return new RateLimiterProfile[] { new RateLimiterProfile(getDefaultRateLimiter(), 1) };
	}

	public <T> CompletableFuture<T> callAsyncWithRetry(SupplierWithException<T> supplier,
			RateLimiterProfile... limiters) {
		return CompletableFuture.supplyAsync( //
				new CommunicationExceptionRetrySupplier<>( //
						new RateLimitedSupplier<>( //
								wrapExceptionHandling( //
										this, supplier //
								), //
								limiters), //
						this, DEFAULT_RETRY_COUNT), //
				executorService);
	}

	public <T> CompletableFuture<T> callAsync(SupplierWithException<T> supplier, RateLimiterProfile... limiters) {
		return CompletableFuture.supplyAsync(
				new RateLimitedSupplier<T>(wrapExceptionHandling(this, supplier), limiters), executorService);
	}

	public <T> T callSyncWithRetry(SupplierWithException<T> supplier, RateLimiterProfile... limiters) {
		return new CommunicationExceptionRetrySupplier<>(() -> {
			T result = wrapExceptionHandling(this, supplier).get();
			if (ArrayUtils.isNotEmpty(limiters))
				for (RateLimiterProfile l : limiters)
					if (l != null)
						l.acquire();
			return result;
		}, this, DEFAULT_RETRY_COUNT).get();
	}

	public <T> T callSync(SupplierWithException<T> supplier, RateLimiterProfile... limiters) {
		T result = wrapExceptionHandling(this, supplier).get();
		if (ArrayUtils.isNotEmpty(limiters))
			for (RateLimiterProfile l : limiters)
				if (l != null)
					l.acquire();
		return result;
	}

	@Override
	public Map<String, Runnable> getBackgroundJobs(MarketPairsProvider provider) {
		return new HashMap<>();
	}

	// -------------------------------------------- Common Methods

	@Override
	public int hashCode() {
		return getName().hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj instanceof AbstractBlackbirdExchange == false)
			return false;
		AbstractBlackbirdExchange a = (AbstractBlackbirdExchange) obj;
		return getName().equals(a.getName());
	}

	@Override
	public int compareTo(BlackbirdExchange e) {
		if (equals(e))
			return 0;
		return new CompareToBuilder().append(getName().toLowerCase(), e.getName().toLowerCase()).toComparison() > 0 ? 1
				: -1;
	}

	@Override
	public final String toString() {
		return getName();
	}

	// -------------------------------------------- Accessor Methods

	public BigDecimal getFeePercentage() {
		return feePercentage;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public int getMaxRequestsPerTenMinutes() {
		return maxRequestsPerTenMinutes;
	}

	public LocalDateTime getDisabledUntilDate() {
		return disabledUntilDate;
	}

	public List<CurrencyPair> getCurrencyPairsForShortPositions() {
		if (currenciesForShortPositions == null)
			currenciesForShortPositions = new ArrayList<>();
		return currenciesForShortPositions;
	}

	public List<CurrencyPair> getCurrencyPairsForLongPositions() {
		if (currenciesForLongPositions == null)
			currenciesForLongPositions = new ArrayList<>();
		return currenciesForLongPositions;
	}

}
