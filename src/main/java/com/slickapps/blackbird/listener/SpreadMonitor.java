package com.slickapps.blackbird.listener;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;
import com.slickapps.blackbird.model.SpreadBounds;

public class SpreadMonitor extends DefaultBlackbirdEventListener {

	/*
	 * For both ExchangePairsInMarket and those out of market, this tracks the
	 * spreads. When moving in or out of the market, we reset these.
	 */
	private ConcurrentHashMap<ExchangePairAndCurrencyPair, SpreadBounds> spreadsByExchangeAndCurrency = new ConcurrentHashMap<>();

	private Parameters params;

	public SpreadMonitor(Parameters params) {
		this.params = params;
	}

	@Override
	public void quotePairEvaluation(QuotePair quotePair, boolean entryNotExit) {
		Quote longQuote = quotePair.getLongQuote();
		Quote shortQuote = quotePair.getShortQuote();

		/* Update the informational spread tracker */
		ExchangePairAndCurrencyPair ecp = new ExchangePairAndCurrencyPair(longQuote, shortQuote);
		BigDecimal spread = entryNotExit ? quotePair.getSpreadIfEntering() : quotePair.getSpreadIfExiting();

		SpreadBounds spreadBounds = spreadsByExchangeAndCurrency.computeIfAbsent(ecp, p -> createNewSpreadBounds());
		spreadBounds.input(spread);
	}

	protected SpreadBounds createNewSpreadBounds() {
		return new SpreadBounds(params.spreadAverageWindowLengthSeconds, params.spreadWindowValidAfterSeconds);
	}

	@Override
	public void entryOrdersFilled(ExchangePairInMarket e) {
		/*
		 * Prepare for tracking the spreads so we know when to close the open order
		 */
		resetSpreads(e);
	}

	@Override
	public void marketPairRemoved(ExchangePairInMarket e) {
		/*
		 * Prepare for tracking the spreads for new market orders on this currency pair
		 */
		resetSpreads(e);
	}

	private void resetSpreads(ExchangePairInMarket e) {
		spreadsByExchangeAndCurrency.computeIfAbsent(e.toExchangePairAndCurrencyPair(), p -> createNewSpreadBounds())
				.reset();
	}

	public Optional<SpreadBounds> getSpreadBounds(ExchangePairAndCurrencyPair e) {
		return Optional.ofNullable(spreadsByExchangeAndCurrency.get(e));
	}

}
