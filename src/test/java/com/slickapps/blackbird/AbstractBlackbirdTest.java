package com.slickapps.blackbird;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.Parameters;

public abstract class AbstractBlackbirdTest {

	@Before
	public final void init() throws IOException {
		lastStep = 0;
	}

	protected List<BlackbirdExchange> exchanges;
	protected TestMain main;
	protected int lastStep = 0;

	protected void generateQuotesForAllExchanges() {
		for (BlackbirdExchange e : exchanges)
			e.queryForQuote(CurrencyPair.BTC_USD);
	}

	protected void advanceToStep(int step) throws IOException, InterruptedException, ExecutionException {
		while (lastStep < step) {
			main.generateNewQuotes();
			main.processNewQuotes();
			lastStep++;
		}
	}

	protected Parameters createTestParameters() throws IOException {
		Parameters params = new Parameters();

		Properties props = new Properties();
		try (final InputStream stream = getClass().getResourceAsStream("/blackbird-test.conf")) {
			props.load(stream);
		}
		params.setFromProperties(props);

		return params;
	}

}
