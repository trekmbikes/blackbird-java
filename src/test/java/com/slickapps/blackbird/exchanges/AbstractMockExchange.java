package com.slickapps.blackbird.exchanges;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.exceptions.ExchangeException;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.util.RateLimiterProfile;
import com.slickapps.blackbird.util.exception.ExceptionUtil;
import com.slickapps.blackbird.util.exception.SupplierWithException;

public abstract class AbstractMockExchange<U extends AbstractMockExchange<U>> extends AbstractBlackbirdExchange {

	Map<String, Long> orderSubmittedTimes = new HashMap<>();
	BigDecimal amountPurchased = ZERO;
	String name;

	protected AbstractMockExchange(String name) {
		this.name = name;
	}

	@SuppressWarnings("unchecked")
	public U makeShortable() {
		getCurrencyPairsForShortPositions().add(CurrencyPair.BTC_USD);
		return ((U) this);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	protected Exchange createExchangeInstance() {
		return null;
	}

	@Override
	protected ExchangeSpecification getExchangeSpec() throws ExchangeException {
		return null;
	}

	@Override
	public <T> CompletableFuture<T> callAsync(SupplierWithException<T> supplier,
			RateLimiterProfile... limiterProfiles) {
		return CompletableFuture.completedFuture(ExceptionUtil.wrapExceptionHandling(this, supplier).get());
	}

	@Override
	public <T> CompletableFuture<T> callAsyncWithRetry(SupplierWithException<T> supplier,
			RateLimiterProfile... limiterProfiles) {
		return callAsync(supplier);
	}

	public void setParams(Parameters params) {
		this.params = params;
	}

}
