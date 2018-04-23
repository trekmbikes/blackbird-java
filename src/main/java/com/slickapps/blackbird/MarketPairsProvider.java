package com.slickapps.blackbird;

import java.util.SortedSet;

import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairsInMarket;

public interface MarketPairsProvider {

	ExchangePairsInMarket getPairsInMarket();

	SortedSet<ExchangePairAndCurrencyPair> getPairsOutOfMarket();

}
