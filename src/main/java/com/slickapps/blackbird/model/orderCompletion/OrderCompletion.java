package com.slickapps.blackbird.model.orderCompletion;

import org.knowm.xchange.dto.Order;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

public class OrderCompletion {

	public BlackbirdExchange exchange;
	public OrderCompletionStatus status;
	public Order completedOrder;
	public String orderId;
	public Throwable errorOccurred;

	public OrderCompletion(BlackbirdExchange exchange, OrderCompletionStatus status, String orderId,
			Order completedOrder, Throwable errorOccurred) {
		this.exchange = exchange;
		this.status = status;
		this.orderId = orderId;
		this.completedOrder = completedOrder;
		this.errorOccurred = errorOccurred;
	}
}