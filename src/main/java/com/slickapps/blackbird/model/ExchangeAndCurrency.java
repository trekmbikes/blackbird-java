package com.slickapps.blackbird.model;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.knowm.xchange.currency.Currency;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

/**
 * Just a simple union structure of ArbitrageExchange and Currency so we can use
 * it as a tuple elsewhere
 * 
 * @author barrycon
 *
 */
public class ExchangeAndCurrency implements Comparable<ExchangeAndCurrency> {

	private BlackbirdExchange exchange;
	private Currency currency;

	public ExchangeAndCurrency(BlackbirdExchange exchange, Currency currency) {
		this.exchange = exchange;
		this.currency = currency;
	}

	@Override
	public int compareTo(ExchangeAndCurrency o) {
		if (equals(o))
			return 0;
		return new CompareToBuilder().append(exchange, o.exchange)
				.append(String.valueOf(currency), String.valueOf(o.currency)).toComparison() > 0 ? 1 : -1;
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(currency).append(exchange).toHashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		ExchangeAndCurrency other = (ExchangeAndCurrency) obj;
		return new EqualsBuilder().append(currency, other.currency).append(exchange, other.exchange).isEquals();
	}

	public BlackbirdExchange getExchange() {
		return exchange;
	}

	public Currency getCurrency() {
		return currency;
	}

}
