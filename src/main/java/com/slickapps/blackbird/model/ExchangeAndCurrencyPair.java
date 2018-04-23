package com.slickapps.blackbird.model;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

/**
 * Just a simple union structure of ArbitrageExchange and CurrencyPair so we can
 * use it as a tuple elsewhere
 * 
 * @author barrycon
 *
 */
public class ExchangeAndCurrencyPair implements Comparable<ExchangeAndCurrencyPair> {

	private BlackbirdExchange exchange;
	private CurrencyPair currencyPair;

	public ExchangeAndCurrencyPair(BlackbirdExchange exchange, CurrencyPair currency) {
		this.exchange = exchange;
		this.currencyPair = currency;
	}

	@Override
	public int compareTo(ExchangeAndCurrencyPair o) {
		if (equals(o))
			return 0;
		return new CompareToBuilder() //
				.append(String.valueOf(currencyPair), String.valueOf(o.currencyPair)) //
				.append(exchange, o.exchange) //
				.toComparison() > 0 ? 1 : -1;
	}

	public boolean isShortable() {
		return exchange.getCurrencyPairsForShortPositions().contains(currencyPair);
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(currencyPair).append(exchange).toHashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		ExchangeAndCurrencyPair other = (ExchangeAndCurrencyPair) obj;
		return new EqualsBuilder().append(currencyPair, other.currencyPair).append(exchange, other.exchange).isEquals();
	}

	public BlackbirdExchange getExchange() {
		return exchange;
	}

	public CurrencyPair getCurrencyPair() {
		return currencyPair;
	}

}
