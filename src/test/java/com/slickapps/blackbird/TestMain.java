package com.slickapps.blackbird;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.slickapps.blackbird.exchanges.AbstractMockExchange;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.BlackbirdEventListener;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.listener.VolatilityMonitor;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.TrailingDetails;
import com.slickapps.blackbird.service.BalanceService;
import com.slickapps.blackbird.service.TestMarketEntryService;
import com.slickapps.blackbird.service.TestMarketExitService;
import com.slickapps.blackbird.service.TestQuoteService;

public class TestMain extends Main {

	List<BlackbirdExchange> exchangeOverrides;

	public TestMain(Parameters params, List<BlackbirdExchange> exchangeOverrides) throws Exception {
		super(params);
		this.exchangeOverrides = exchangeOverrides;
		for (BlackbirdExchange e : exchangeOverrides)
			((AbstractMockExchange<?>) e).setParams(params);
	}

	@Override
	public void initResources() throws Exception, IOException {
		initExchanges(exchangeOverrides);

		exchangePairsInMarket = createOrImportPairsInMarket();
		
		eventListeners.add(spreadMonitor = new SpreadMonitor(params));
		eventListeners.add(volatilityMonitor = new VolatilityMonitor());

		quoteService = new TestQuoteService(params, this);
		marketEntryService = new TestMarketEntryService(params, this, this, quoteService, spreadMonitor);
		
		for (BlackbirdEventListener l : eventListeners)
			l.init(exchanges, this, params);

		/* Init services */
		
		marketExitService = new TestMarketExitService(params, this, this, quoteService, spreadMonitor);

		balanceService = new BalanceService(params);
		balanceService.populateAndValidateBalances(exchanges, exchangePairsInMarket);

		quoteService.initAndStartQuoteGenerators(exchanges);
	}

	public void generateNewQuotes() throws InterruptedException, ExecutionException {
		((TestQuoteService) quoteService).generateAllQuotes();
	}

	/* Expand the scope to public for the purpose of unit testing */
	@Override
	public void processNewQuotes() throws IOException, InterruptedException, ExecutionException {
		super.processNewQuotes();
	}

	public Map<ExchangePairAndCurrencyPair, TrailingDetails> getEntryTrailingDetailsMap() {
		return ((TestMarketEntryService) marketEntryService).getTrailingStopFilter().getTrailingMap();
	}

	/**
	 * Gets the first TrailingDetails stored in the entry service (or null if none
	 * yet exists)
	 * 
	 * @return
	 */
	public TrailingDetails getEntryTrailingDetails() {
		Map<ExchangePairAndCurrencyPair, TrailingDetails> map = getEntryTrailingDetailsMap();
		return map.isEmpty() ? null : map.values().iterator().next();
	}

	public Map<ExchangePairAndCurrencyPair, TrailingDetails> getExitTrailingDetailsMap() {
		return ((TestMarketExitService) marketExitService).getTrailingStopFilter().getTrailingMap();
	}

	/**
	 * Gets the first TrailingDetails stored in the entry service (or null if none
	 * yet exists)
	 * 
	 * @return
	 */
	public TrailingDetails getExitTrailingDetails() {
		Map<ExchangePairAndCurrencyPair, TrailingDetails> map = getExitTrailingDetailsMap();
		return map.isEmpty() ? null : map.values().iterator().next();
	}

}
