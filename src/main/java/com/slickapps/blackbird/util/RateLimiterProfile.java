package com.slickapps.blackbird.util;

import java.util.concurrent.TimeUnit;

import org.knowm.xchange.binance.dto.meta.exchangeinfo.RateLimit;

import com.google.common.util.concurrent.RateLimiter;

public class RateLimiterProfile {
	RateLimiter limiter;
	int numPermits;

	public RateLimiterProfile(RateLimiter limiter, int numPermits) {
		if (limiter == null)
			throw new NullPointerException("RateLimiter cannot be null");
		this.limiter = limiter;
		this.numPermits = numPermits;
	}

	public void acquire() {
		limiter.acquire(numPermits);
	}

	public static double getRatePerSecond(RateLimit l) {
		TimeUnit interval = TimeUnit.valueOf(l.getInterval().toUpperCase() + "S");
		return Double.parseDouble(l.getLimit()) * 1.0 / interval.toSeconds(1);
	}
}