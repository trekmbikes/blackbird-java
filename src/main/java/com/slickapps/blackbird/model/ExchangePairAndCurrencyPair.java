package com.slickapps.blackbird.model;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

/**
 * Just a simple union structure of two ArbitrageExchanges and CurrencyPairs so
 * we can use it as a tuple elsewhere
 * 
 * @author barrycon
 *
 */
public class ExchangePairAndCurrencyPair implements Comparable<ExchangePairAndCurrencyPair> {

	private BlackbirdExchange longExchange;
	private CurrencyPair longCurrencyPair;

	private BlackbirdExchange shortExchange;
	private CurrencyPair shortCurrencyPair;

	public ExchangePairAndCurrencyPair(Quote longQuote, Quote shortQuote) {
		this(longQuote.getExchange(), longQuote.getCurrencyPair(), shortQuote.getExchange(),
				shortQuote.getCurrencyPair());
	}

	public ExchangePairAndCurrencyPair(BlackbirdExchange longExchange, CurrencyPair longCurrencyPair,
			BlackbirdExchange shortExchange, CurrencyPair shortCurrencyPair) {
		this.longExchange = longExchange;
		this.longCurrencyPair = longCurrencyPair;

		this.shortExchange = shortExchange;
		this.shortCurrencyPair = shortCurrencyPair;
	}

	public ExchangePairAndCurrencyPair(ExchangeAndCurrencyPair longPair, ExchangeAndCurrencyPair shortPair) {
		this(longPair.getExchange(), longPair.getCurrencyPair(), shortPair.getExchange(), shortPair.getCurrencyPair());
	}

	@Override
	public int compareTo(ExchangePairAndCurrencyPair o) {
		if (equals(o))
			return 0;
		return new CompareToBuilder() //
				.append(String.valueOf(longCurrencyPair), String.valueOf(o.longCurrencyPair)) //
				.append(longExchange, o.longExchange) //
				.append(String.valueOf(shortCurrencyPair), String.valueOf(o.shortCurrencyPair)) //
				.append(shortExchange, o.shortExchange) //
				.toComparison() > 0 ? 1 : -1;
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(longExchange).append(longCurrencyPair).append(shortExchange)
				.append(shortCurrencyPair).toHashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;

		ExchangePairAndCurrencyPair o = (ExchangePairAndCurrencyPair) obj;
		return new EqualsBuilder().append(longCurrencyPair, o.longCurrencyPair)
				.append(shortCurrencyPair, o.shortCurrencyPair).append(longExchange, o.longExchange)
				.append(shortExchange, o.shortExchange).isEquals();
	}

	public ExchangeAndCurrencyPair getLongExchangeAndCurrencyPair() {
		return new ExchangeAndCurrencyPair(longExchange, longCurrencyPair);
	}

	public ExchangeAndCurrencyPair getShortExchangeAndCurrencyPair() {
		return new ExchangeAndCurrencyPair(shortExchange, shortCurrencyPair);
	}

	public BlackbirdExchange getShortExchange() {
		return shortExchange;
	}

	public BlackbirdExchange getLongExchange() {
		return longExchange;
	}

	public CurrencyPair getLongCurrencyPair() {
		return longCurrencyPair;
	}

	public CurrencyPair getShortCurrencyPair() {
		return shortCurrencyPair;
	}

}
