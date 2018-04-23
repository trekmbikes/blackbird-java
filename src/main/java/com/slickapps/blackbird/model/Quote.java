package com.slickapps.blackbird.model;

import static com.slickapps.blackbird.Main.TWO;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.BigDecimal.ZERO;
import static java.math.MathContext.DECIMAL64;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

public class Quote {

	private ExchangeAndCurrencyPair exchangeAndCurrencyPair;
	private BigDecimal bid;
	private BigDecimal ask;
	private LocalDateTime creationTime;

	public Quote(ExchangeAndCurrencyPair exchangeAndCurrencyPair, BigDecimal bid, BigDecimal ask) {
		if (exchangeAndCurrencyPair == null || bid == null || ask == null)
			throw new IllegalArgumentException("All parameters must be non-null");
		
		this.exchangeAndCurrencyPair = exchangeAndCurrencyPair;
		this.bid = bid;
		this.ask = ask;
		this.creationTime = LocalDateTime.now();
	}

	public BigDecimal getMidPrice() {
		if (bid != null && ask != null && bid.signum() == 1 && ask.signum() == 1) {
			return bid.add(ask).divide(TWO, DECIMAL64);
		} else {
			return ZERO;
		}
	}

	public BlackbirdExchange getExchange() {
		return exchangeAndCurrencyPair.getExchange();
	}

	public CurrencyPair getCurrencyPair() {
		return exchangeAndCurrencyPair.getCurrencyPair();
	}

	public String toString() {
		Currency currency = getCurrencyPair().counter;
		return creationTime + ": Bid=" + formatCurrency(currency, bid) + ", Ask=" + formatCurrency(currency, ask);
	}

	public ExchangeAndCurrencyPair getExchangeAndCurrencyPair() {
		return exchangeAndCurrencyPair;
	}

	public BigDecimal getBid() {
		return bid;
	}

	public BigDecimal getAsk() {
		return ask;
	}

	public LocalDateTime getCreationTime() {
		return creationTime;
	}

}
