package com.slickapps.blackbird.model;

import org.knowm.xchange.dto.Order;

public class OrderPair {

	public Order order1;
	public Order order2;

	public OrderPair(Order order1, Order order2) {
		this.order1 = order1;
		this.order2 = order2;
	}

}
