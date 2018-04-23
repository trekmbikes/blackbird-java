package com.slickapps.blackbird.util.exception;

import static com.slickapps.blackbird.util.exception.ExceptionUtil.wrapExchangeExceptionIfNeeded;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

public class CommunicationExceptionRetrySupplier<T> implements Supplier<T> {
	private static final Logger log = LoggerFactory.getLogger(CommunicationExceptionRetrySupplier.class);

	private BlackbirdExchange exchange;
	private Supplier<T> delegate;
	private int numRetries;

	public CommunicationExceptionRetrySupplier(Supplier<T> callable, BlackbirdExchange exchange, int numRetries) {
		this.delegate = callable;
		this.exchange = exchange;
		this.numRetries = numRetries;
	}

	@Override
	public T get() {
		Exception last = null;
		outer: //
		for (int i = 0; i < numRetries; i++) {
			try {
				T result = delegate.get();
				return result;
			} catch (Exception e) {
				if (ExceptionUtil.isRetryable(exchange, e)) {
					log.warn("Communication error with exchange " + exchange + ", retrying " + (i + 1) + "/"
							+ numRetries + "...");
					last = e;
					continue outer;
				}
				throw wrapExchangeExceptionIfNeeded(exchange, e);
			}
		}

		throw wrapExchangeExceptionIfNeeded(exchange, last);
	}
}