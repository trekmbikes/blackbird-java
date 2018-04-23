package com.slickapps.blackbird.util;

import java.util.function.Supplier;

import org.apache.commons.lang3.ArrayUtils;

public class RateLimitedSupplier<T> implements Supplier<T> {

	private RateLimiterProfile[] rateLimiterProfiles;
	private Supplier<T> delegate;

	public RateLimitedSupplier(Supplier<T> callable, RateLimiterProfile... rateLimiterProfiles) {
		this.rateLimiterProfiles = rateLimiterProfiles;
		this.delegate = callable;
	}

	@Override
	public T get() {
		T result = delegate.get();
		if (ArrayUtils.isNotEmpty(rateLimiterProfiles))
			for (RateLimiterProfile l : rateLimiterProfiles)
				if (l != null)
					l.acquire();
		return result;
	}

}