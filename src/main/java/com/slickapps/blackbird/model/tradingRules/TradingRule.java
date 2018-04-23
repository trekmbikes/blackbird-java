package com.slickapps.blackbird.model.tradingRules;

import java.math.BigDecimal;
import java.util.SortedSet;
import java.util.TreeSet;

public class TradingRule {

	private BigDecimal minQuantity;
	private BigDecimal minPrice;
	private BigDecimal minTotal;

	private BigDecimal stepSizeForQuantity;
	private BigDecimal stepSizeForPrice;
	// TODO stepSizeForTotal?

	private SortedSet<Integer> leveragesSupported;

	// ---------------------------------------- Accessor Methods

	public BigDecimal getMinQuantity() {
		return minQuantity;
	}

	public void setMinQuantity(BigDecimal minQuantity) {
		this.minQuantity = minQuantity;
	}

	public BigDecimal getMinPrice() {
		return minPrice;
	}

	public void setMinPrice(BigDecimal minPrice) {
		this.minPrice = minPrice;
	}

	public BigDecimal getMinTotal() {
		return minTotal;
	}

	public void setMinTotal(BigDecimal minTotal) {
		this.minTotal = minTotal;
	}

	public BigDecimal getStepSizeForQuantity() {
		return stepSizeForQuantity;
	}

	public void setStepSizeForQuantity(BigDecimal quantityStepSize) {
		this.stepSizeForQuantity = quantityStepSize;
	}

	public BigDecimal getStepSizeForPrice() {
		return stepSizeForPrice;
	}

	public void setStepSizeForPrice(BigDecimal stepSizeForPrice) {
		this.stepSizeForPrice = stepSizeForPrice;
	}

	public SortedSet<Integer> getLeveragesSupported() {
		if (leveragesSupported == null)
			leveragesSupported = new TreeSet<>();
		return leveragesSupported;
	}

	public void setLeveragesSupported(SortedSet<Integer> leveragesSupported) {
		this.leveragesSupported = leveragesSupported;
	}

}
