package com.slickapps.blackbird.model;

import java.math.BigDecimal;

import org.apache.commons.lang3.builder.HashCodeBuilder;

public class BigDecimalPair {

	private BigDecimal longVal;
	private BigDecimal shortVal;

	public BigDecimalPair(BigDecimal longVal, BigDecimal shortVal) {
		if (longVal == null || shortVal == null)
			throw new IllegalArgumentException("Both longVal and shortVal are required");

		this.longVal = longVal;
		this.shortVal = shortVal;
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(longVal).append(shortVal).toHashCode();
	}

	/* Implements equality via compareTo() of BigDecimal */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		BigDecimalPair other = (BigDecimalPair) obj;
		if (longVal == null) {
			if (other.longVal != null)
				return false;
		} else if (longVal.compareTo(other.longVal) != 0)
			return false;
		if (shortVal == null) {
			if (other.shortVal != null)
				return false;
		} else if (shortVal.compareTo(other.shortVal) != 0)
			return false;
		return true;
	}

	public BigDecimal getLong() {
		return longVal;
	}

	public BigDecimal getShort() {
		return shortVal;
	}
}
