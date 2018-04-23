package com.slickapps.blackbird.util.exception;

import java.util.concurrent.CompletionException;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

public class ExchangeRuntimeException extends CompletionException {
	private static final long serialVersionUID = -4197228023922305614L;

	private BlackbirdExchange exchange;

	public ExchangeRuntimeException(BlackbirdExchange exchange, String message, Throwable cause) {
		super(message, cause);
		if (cause == null)
			throw new IllegalArgumentException("Cause required for " + getClass().getName());
		this.exchange = exchange;
	}

	public BlackbirdExchange getExchange() {
		return exchange;
	}

}
