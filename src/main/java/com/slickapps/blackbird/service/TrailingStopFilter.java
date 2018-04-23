package com.slickapps.blackbird.service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.TrailingDetails;
import com.slickapps.blackbird.util.FormatUtil;

/**
 * A class which implements trailing stop logic. See
 * https://github.com/butor/blackbird/issues/12
 */
public class TrailingStopFilter {
	private static final Logger log = LoggerFactory.getLogger(TrailingStopFilter.class);

	Map<ExchangePairAndCurrencyPair, TrailingDetails> trailingMap = new ConcurrentHashMap<>();
	Parameters params;
	boolean isEntry;

	public TrailingStopFilter(Parameters params, boolean isEntry) {
		this.params = params;
		this.isEntry = isEntry;
	}

	public boolean evaluate(ExchangePairAndCurrencyPair ecp, BigDecimal currentSpread, BigDecimal targetSpread) {
		TrailingDetails trailing = getOrCreateTrailingDetails(ecp);

		/*
		 * If the spread of our current quote pair is less than our target (on entry) or
		 * greater than our target (on exit), we want to immediately quit - this means
		 * we couldn't even achieve our minimum viable target. The target will slowly
		 * fade up and down since it's based on the window average; the implication is
		 * that we might have previously approved a spread but due to the average moving
		 * it would disqualify our previous trailing value and we'll need to
		 * reestablish. This seems reasonable since we want to remain aligned to the
		 * window average.
		 */
		if ((isEntry && currentSpread.compareTo(targetSpread) < 0)
				|| (!isEntry && currentSpread.compareTo(targetSpread) > 0)) {
			trailing.reset();
			return false;
		}

		/*
		 * Ok, we met or exceeded the target which means we're on the way to approving
		 * entry into the market. Calculate the new trailing value based on the current
		 * spread.
		 */
		BigDecimal newTrailValue = isEntry ? currentSpread.subtract(params.trailingSpreadLim)
				: currentSpread.add(params.trailingSpreadLim);

		/*
		 * If this is the first iteration in which we have exceeded our target, our
		 * trailingDetails will not yet have a trailing stop set. Set it here and return
		 * for now, we'll check the next quote's spread. Unfortunately this means if our
		 * spread drops below the target on the second iteration, we would have missed
		 * our first and only opportunity to capture the time it exceeded our target;
		 * this is a worthwhile tradeoff since the majority of the time we'll have
		 * multiple iterations in which our spread exceeds our target.
		 */
		if (!trailing.hasTrailingSpread()) {
			NumberFormat pctF = FormatUtil.getPercentFormatter();
			log.debug("Establishing trailing stop at {}", pctF.format(newTrailValue));
			/* Just to be safe */
			trailing.reset();

			trailing.setTrailingStop(newTrailValue
			// C version applied this operation but I don't see why it's necessary
			// .max(targetEntrySpread)
			);

			return false;
		}

		/* We already have a trailing spread, so we're two or more iterations in. */
		BigDecimal currentTrailingStop = trailing.getTrailingStop();

		/*
		 * If our new trailing value exceeds our previous trailing value, use the new
		 * value going forward. Since we just changed the trailing value, we better
		 * reset the counter to 1.
		 */
		if (newTrailValue.compareTo(currentTrailingStop) != (isEntry ? -1 : 1)) {
			currentTrailingStop = newTrailValue;
			trailing.setTrailingStop(newTrailValue);
			trailing.resetRequiredConfirmationPeriods();
		}

		/*
		 * Regardless whether we updated our trailing value above, if our new spread
		 * value exceeds our trailing value we need to reset our counter back to 1.
		 */
		if (currentSpread.compareTo(currentTrailingStop) != (isEntry ? -1 : 1)) {
			trailing.resetRequiredConfirmationPeriods();
			return false;
		}

		/*
		 * Progress, our current spread is below our trailing stop! If our configuration
		 * requires us to wait a couple iterations for this condition (per the
		 * "trailingCount" property) then we increment the counter here; otherwise, we
		 * are approved to create orders and enter the market.
		 */
		if (trailing.getTrailingStopApprovalCount() < params.trailingRequiredConfirmationPeriods) {
			trailing.incrementTrailingStopApprovalCount();
			return false;
		}

		return true;
	}

	public TrailingDetails getOrCreateTrailingDetails(ExchangePairAndCurrencyPair ecp) {
		return trailingMap.computeIfAbsent(ecp, p -> new TrailingDetails());
	}

	public Map<ExchangePairAndCurrencyPair, TrailingDetails> getTrailingMap() {
		return trailingMap;
	}

}
