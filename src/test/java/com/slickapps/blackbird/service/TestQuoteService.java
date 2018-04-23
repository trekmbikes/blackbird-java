package com.slickapps.blackbird.service;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.processes.QuoteGenerator;

public class TestQuoteService extends QuoteService {

	public TestQuoteService(Parameters params, EventListenerProvider eventListenerProvider) {
		super(params, eventListenerProvider);
	}

	@Override
	protected void startThreads(List<QuoteGenerator> quoteGenerators) {
		// skip starting threads since we'll generate events manually
	}

	public void generateAllQuotes() throws InterruptedException, ExecutionException {
		for (QuoteGenerator gen : quoteGenerators) {
			gen.generateNextQuote();
		}
	}

}
