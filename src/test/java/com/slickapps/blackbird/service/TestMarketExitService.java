package com.slickapps.blackbird.service;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.model.Parameters;

public class TestMarketExitService extends MarketExitService {

	public TestMarketExitService(Parameters params, MarketPairsProvider marketPairsProvider,
			EventListenerProvider eventListenerProvider, QuoteService quoteService, SpreadMonitor spreadMonitor) {
		super(params, marketPairsProvider, eventListenerProvider, quoteService, spreadMonitor);
	}

	public TrailingStopFilter getTrailingStopFilter() {
		return trailingStopFilter;
	}

}
