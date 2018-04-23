package com.slickapps.blackbird.listener;

import java.util.List;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;

public interface BlackbirdEventListener {

	void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params) throws Exception;

	void quoteReceived(Quote q);

	void entryOrdersPlaced(ExchangePairInMarket e);

	void entryOrdersFilled(ExchangePairInMarket epim);

	void exitOrdersPlaced(ExchangePairInMarket newExit);

	void marketPairRemoved(ExchangePairInMarket epim);

	void programExit() throws Exception;

	void quotePairEvaluation(QuotePair quotePair, boolean entryNotExit);

	void orderComplete(ExchangePairInMarket p);

}
