package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.Main.TWO;
import static java.math.MathContext.DECIMAL64;
import static org.knowm.xchange.dto.Order.OrderType.BID;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.Wallet;

import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.util.FormatUtil;
import com.slickapps.blackbird.util.exception.SupplierWithException;

public class MockExchange extends AbstractMockExchange<MockExchange> {

	int quoteQueryCount = 0;
	int nextIterationIndex = 0;
	double lastValue;

	double[][] iterationNumbersAndValues;

	/**
	 * @param params
	 * @param iterationNumbersAndValues
	 *            A two dimensional array that looks like {{5, 8000}, {15, 8005},
	 *            ...} where 5 means the 5th quote returned AFTER the initialVal (so
	 *            the 6th overall), and 15 means the 15th.
	 */
	public MockExchange(String name, double feePercentage, double initialVal, double[][] iterationNumbersAndValues) {
		super(name);
		this.feePercentage = new BigDecimal(feePercentage / 100);
		this.lastValue = initialVal;
		getCurrencyPairsForLongPositions().add(CurrencyPair.BTC_USD);
		this.iterationNumbersAndValues = iterationNumbersAndValues;
	}

	public MockExchange(String name, double feePercentage, double initialVal) {
		this(name, feePercentage, initialVal, new double[][] {});
	}

	@Override
	public final CompletableFuture<Quote> queryForQuote(CurrencyPair currencyPair) {
		quoteQueryCount++;

		if (nextIterationIndex < iterationNumbersAndValues.length
				&& quoteQueryCount >= iterationNumbersAndValues[nextIterationIndex][0]) {
			lastValue = iterationNumbersAndValues[nextIterationIndex][1];
			nextIterationIndex++;
		}

		return CompletableFuture.completedFuture(new Quote(new ExchangeAndCurrencyPair(this, currencyPair),
				new BigDecimal(lastValue - 0.01), new BigDecimal(lastValue + 0.01)));
	}

	@Override
	protected SupplierWithException<Wallet> getWalletSupplier() {
		return () -> {
			walletCache = new Wallet(new Balance(Currency.USD, getUSDAvailable()),
					new Balance(Currency.BTC, amountPurchased));

			walletLastUpdated = LocalDateTime.now();
			return walletCache;
		};
	}

	@Override
	public CompletableFuture<String> openLongPosition(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		log.info("{} submitting long order...", getClass().getSimpleName());
		log.info("{} long order submitted.", getClass().getSimpleName());
		orderSubmittedTimes.put("LongOrderId", System.currentTimeMillis());
		if (currencyPair.base.equals(Currency.BTC)) {
			walletLastUpdated = null;
			amountPurchased = quantity;
		}
		return CompletableFuture.completedFuture("LongOrderId");
	}

	@Override
	protected CompletableFuture<String> openShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		log.info("{} submitting short order...", getClass().getSimpleName());
		log.info("{} short order submitted.", getClass().getSimpleName());
		orderSubmittedTimes.put("ShortOrderId", System.currentTimeMillis());
		if (currencyPair.base.equals(Currency.BTC)) {
			walletLastUpdated = null;
			amountPurchased = quantity;
		}
		return CompletableFuture.completedFuture("ShortOrderId");
	}

	@Override
	protected CompletableFuture<String> sendLimitOrder(OrderType orderType, CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		log.info("{} submitting limit order...", getClass().getSimpleName());
		log.info("{} limit order submitted.", getClass().getSimpleName());
		orderSubmittedTimes.put("LimitOrderId", System.currentTimeMillis());
		return CompletableFuture.completedFuture("LimitOrderId");
	}

	@Override
	public CompletableFuture<BigDecimal> queryLimitPrice(CurrencyPair currencyPair, BigDecimal volume,
			OrderType orderType) {
		log.info("Looking for a limit price to fill {} on {}...", FormatUtil.formatCurrency(currencyPair.base, volume),
				getName());
		BigDecimal halfLimit = params.maxLimitPriceDifference.divide(TWO, DECIMAL64);
		BigDecimal val = orderType == BID ? DUMMY_MARKET_ORDER_PRICE.subtract(halfLimit)
				: DUMMY_MARKET_ORDER_PRICE.add(halfLimit);
		log.info("Returning limit price of {} on {}.", FormatUtil.formatCurrency(currencyPair.counter, val), getName());
		return CompletableFuture.completedFuture(val);
	}

	protected BigDecimal getUSDAvailable() {
		return new BigDecimal(250);
	}

}
