package com.slickapps.blackbird.model.orderCompletion;

import org.knowm.xchange.dto.Order.OrderStatus;

public enum OrderCompletionStatus {
	CANCELED(OrderStatus.CANCELED), //
	EXPIRED(OrderStatus.EXPIRED), //
	FILLED(OrderStatus.FILLED), //
	REJECTED(OrderStatus.REJECTED), //
	REPLACED(OrderStatus.REPLACED), //
	STOPPED(OrderStatus.STOPPED), //
	TIME_EXPIRED(null), //
	UNRECOVERABLE_EXCEPTION(null);

	private OrderStatus orderStatus;

	private OrderCompletionStatus(OrderStatus orderStatus) {
		this.orderStatus = orderStatus;
	}

	public boolean isComplete() {
		return this.orderStatus != null;
	}

	public static OrderCompletionStatus getFromOrderStatus(OrderStatus orderStatus) {
		if (orderStatus == null)
			throw new IllegalArgumentException("Can't get " + OrderCompletionStatus.class.getSimpleName()
					+ " from a null " + OrderStatus.class.getSimpleName());

		for (OrderCompletionStatus s : values())
			if (s.orderStatus == orderStatus)
				return s;
		return null;
	}
}