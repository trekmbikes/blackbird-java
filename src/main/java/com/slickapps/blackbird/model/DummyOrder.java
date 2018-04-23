package com.slickapps.blackbird.model;

import java.math.BigDecimal;

import org.knowm.xchange.dto.Order.OrderType;

public class DummyOrder {
	BigDecimal quantity;
	BigDecimal price;
	OrderType orderType;

	public DummyOrder(BigDecimal quantity, BigDecimal price, OrderType orderType) {
		this.quantity = quantity;
		this.price = price;
		this.orderType = orderType;
	}
	
	public BigDecimal getTotalValue() {
		return quantity.multiply(price);
	}
}