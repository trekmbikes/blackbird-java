package com.slickapps.blackbird.listener;

import java.util.List;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;

/**
 * Default no-op implementations for BlackbirdEventListener
 * 
 * @author barrycon
 *
 */
public abstract class DefaultBlackbirdEventListener implements BlackbirdEventListener {

	@Override
	public void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params) throws Exception {
	}

	@Override
	public void quoteReceived(Quote q) {
	}

	@Override
	public void entryOrdersPlaced(ExchangePairInMarket e) {
	}

	@Override
	public void entryOrdersFilled(ExchangePairInMarket epim) {
	}

	@Override
	public void exitOrdersPlaced(ExchangePairInMarket newExit) {
	}

	@Override
	public void marketPairRemoved(ExchangePairInMarket epim) {
	}

	@Override
	public void orderComplete(ExchangePairInMarket p) {
	}

	@Override
	public void quotePairEvaluation(QuotePair quotePair, boolean entryNotExit) {
	}

	@Override
	public void programExit() throws Exception {
	}

}
