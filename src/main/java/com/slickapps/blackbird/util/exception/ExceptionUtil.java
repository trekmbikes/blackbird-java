package com.slickapps.blackbird.util.exception;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.knowm.xchange.exceptions.FrequencyLimitExceededException;
import org.knowm.xchange.exceptions.NonceException;
import org.knowm.xchange.exceptions.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;

import si.mazi.rescu.HttpStatusIOException;

public class ExceptionUtil {
	public static final Logger log = LoggerFactory.getLogger(ExceptionUtil.class);

	public static ExchangeRuntimeException wrapExchangeExceptionIfNeeded(BlackbirdExchange exchange, Throwable e1) {
		if (e1 instanceof ExchangeRuntimeException)
			return (ExchangeRuntimeException) e1;
		return new ExchangeRuntimeException(exchange, "Exchange Exception", e1);
	}

	public static boolean isRetryable(BlackbirdExchange exchange, Throwable t) {
		if (t instanceof ExecutionException)
			t = unwrapExecutionExceptionFully((ExecutionException) t);

		/* If we have an error and not an exception, it's too severe to retry */
		if (t instanceof Exception == false)
			return false;

		Exception e = (Exception) t;

		if (e instanceof FrequencyLimitExceededException || e instanceof RateLimitExceededException
				|| e instanceof HttpStatusIOException || e instanceof NonceException)
			return true;

		if (exchange.isExceptionRetryable(e))
			return true;

		return false;
	}

	public static Throwable unwrapExecutionExceptionFully(ExecutionException e) {
		for (Throwable t : ExceptionUtils.getThrowableList(e))
			if (t instanceof ExecutionException == false)
				return t;
		return null;
	}

	public static void disableExchange(BlackbirdExchange exchange) {
		log.warn("Temporarily disabling exchange {}", exchange);
		exchange.disableTemporarily();
	}

	public static <T> Supplier<T> wrapExceptionHandling(BlackbirdExchange a, SupplierWithException<T> r) {
		return new Supplier<T>() {
			@Override
			public T get() {
				try {
					return r.get();
				} catch (UndeclaredThrowableException e) {
					Throwable t = e.getUndeclaredThrowable() != null ? e.getUndeclaredThrowable() : e;
					throw wrapExchangeExceptionIfNeeded(a, t);
				} catch (RuntimeException e) {
					throw e;
				} catch (Exception e) {
					throw wrapExchangeExceptionIfNeeded(a, e);
				}
			}
		};
	}

}
