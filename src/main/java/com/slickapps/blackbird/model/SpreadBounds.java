package com.slickapps.blackbird.model;

import static java.math.BigDecimal.ZERO;
import static java.time.temporal.ChronoUnit.MILLIS;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.LinkedList;

/**
 * @author barrycon
 *
 */
public class SpreadBounds {

	private BigDecimal globalMin;
	private BigDecimal globalMax;

	private int windowLengthSeconds;
	private int windowValidAfterSeconds;
	private boolean windowAverageMet;

	private LinkedList<TimeAndValue> vals = new LinkedList<>();
	private BigDecimal lastSum = ZERO;

	static class TimeAndValue {
		LocalDateTime time;
		BigDecimal value;

		TimeAndValue(LocalDateTime time, BigDecimal value) {
			this.time = time;
			this.value = value;
		}
	}

	public SpreadBounds(int windowLengthSeconds, int windowValidAfterSeconds) {
		if (windowLengthSeconds < windowValidAfterSeconds)
			throw new IllegalArgumentException("windowLengthSeconds must not be less than windowValidAfterSeconds");
		this.windowLengthSeconds = windowLengthSeconds;
		this.windowValidAfterSeconds = windowValidAfterSeconds;
	}

	public synchronized void input(BigDecimal val) {
		if (globalMin == null || val.compareTo(globalMin) == -1)
			globalMin = val;
		if (globalMax == null || val.compareTo(globalMax) == 1)
			globalMax = val;

		LocalDateTime now = LocalDateTime.now();
		vals.addLast(new TimeAndValue(now, val));
		lastSum = lastSum.add(val);

		trimExpired(now.minusSeconds(windowLengthSeconds));
	}

	public synchronized boolean hasWindowAverage() {
		if (windowAverageMet && !vals.isEmpty())
			return true;

		TimeAndValue first = vals.peekFirst();
		boolean met = first != null && !first.time.isAfter(LocalDateTime.now().minusSeconds(windowValidAfterSeconds));
		if (met)
			windowAverageMet = true;
		return met;
	}

	/**
	 * @return If hasWindowAverage() is true, returns the average of all received
	 *         values within the last {windowLengthSeconds} seconds, otherwise null.
	 *         <p>
	 *         Note that this is only an accurate representation of what actually
	 *         happened at the exchange if we have a long enough windowLengthSeconds
	 *         and get consistent values provided to input() on a timely basis; if
	 *         we get a single value at minute 1, and one other value at minute 28,
	 *         the true average is almost certainly not the average of these two.
	 */
	public synchronized BigDecimal getWindowAverage() {
		trimExpired(LocalDateTime.now().minusSeconds(windowLengthSeconds));
		if (!hasWindowAverage() || vals.isEmpty())
			return null;

		return lastSum.divide(new BigDecimal(vals.size()), MathContext.DECIMAL64);
	}

	/**
	 * Returns -1 if no values have been input yet, or we have already established
	 * our window. Otherwise, returns the number of milliseconds expected before our
	 * window is established.
	 */
	public long getMillisUntilWindowMet() {
		if (windowAverageMet)
			return -1;

		TimeAndValue first = vals.peekFirst();
		if (first != null) {
			long num = MILLIS.between(LocalDateTime.now(), first.time.plusSeconds(windowValidAfterSeconds));
			if (num <= 0)
				return -1;
			return num;
		}
		return -1;
	}

	private void trimExpired(LocalDateTime windowStart) {
		while (!vals.isEmpty() && vals.getFirst().time.isBefore(windowStart)) {
			TimeAndValue val = vals.removeFirst();
			lastSum = lastSum.subtract(val.value);
		}
	}

	public synchronized void reset() {
		this.globalMin = null;
		this.globalMax = null;
		lastSum = ZERO;
		windowAverageMet = false;
		vals.clear();
	}

	public BigDecimal getGlobalMin() {
		return globalMin;
	}

	public BigDecimal getGlobalMax() {
		return globalMax;
	}

}
