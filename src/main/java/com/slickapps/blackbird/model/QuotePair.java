package com.slickapps.blackbird.model;

import static java.math.MathContext.DECIMAL64;

import java.math.BigDecimal;

/**
 * Just a simple union structure of a Quote from a long exchange and a Quote
 * from a shortable exchange, so we can use it as a tuple elsewhere
 * 
 * @author barrycon
 *
 */
public class QuotePair {

	private Quote longQuote;
	private Quote shortQuote;

	public QuotePair(Quote longQuote, Quote shortQuote) {
		if (longQuote == null || shortQuote == null)
			throw new IllegalArgumentException("Both longQuote and shortQuote are required");

		this.longQuote = longQuote;
		this.shortQuote = shortQuote;
	}

	/**
	 * @return The spread percentage for entering the market with these two quotes -
	 *         that is, the (shortQuote's bid - longQuote's ask) / (longQuote's
	 *         ask).
	 */
	public BigDecimal getSpreadIfEntering() {
		BigDecimal priceLong = longQuote.getAsk();
		BigDecimal priceShort = shortQuote.getBid();
		BigDecimal spread = priceShort.subtract(priceLong).divide(priceLong, DECIMAL64);
		return spread;
	}

	/**
	 * @return The spread percentage for exiting the market with these two quotes -
	 *         that is, the (shortQuote's bid - longQuote's ask) / (longQuote's
	 *         ask).
	 */
	public BigDecimal getSpreadIfExiting() {
		BigDecimal priceLong = longQuote.getBid();
		BigDecimal priceShort = shortQuote.getAsk();
		BigDecimal spread = priceShort.subtract(priceLong).divide(priceLong, DECIMAL64);
		return spread;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((longQuote == null) ? 0 : longQuote.hashCode());
		result = prime * result + ((shortQuote == null) ? 0 : shortQuote.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		QuotePair other = (QuotePair) obj;
		if (longQuote == null) {
			if (other.longQuote != null)
				return false;
		} else if (!longQuote.equals(other.longQuote))
			return false;
		if (shortQuote == null) {
			if (other.shortQuote != null)
				return false;
		} else if (!shortQuote.equals(other.shortQuote))
			return false;
		return true;
	}

	public Quote getLongQuote() {
		return longQuote;
	}

	public Quote getShortQuote() {
		return shortQuote;
	}

}
