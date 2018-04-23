package com.slickapps.blackbird.service;

import static java.lang.Math.abs;
import static java.time.temporal.ChronoUnit.MILLIS;
import static org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;
import com.slickapps.blackbird.processes.QuoteGenerator;

public class QuoteService {
	private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

	Parameters params;
	Map<ExchangeAndCurrencyPair, Quote> liveQuotes = new ConcurrentHashMap<>();
	AtomicLong quoteArrivedCounter = new AtomicLong();
	EventListenerProvider eventListenerProvider;
	List<QuoteGenerator> quoteGenerators;

	private long counterVal = 0;
	private long lastCounterVal = 0;

	public QuoteService(Parameters params, EventListenerProvider eventListenerProvider) {
		this.params = params;
		this.eventListenerProvider = eventListenerProvider;
	}

	public void initAndStartQuoteGenerators(Collection<? extends BlackbirdExchange> exchanges) {
		log.info("Starting quote generators...");
		quoteGenerators = new ArrayList<>();
		for (BlackbirdExchange e : exchanges) {
			if (!e.isEnabled())
				continue;

			QuoteGenerator generator = e.createQuoteGenerator(this, eventListenerProvider);
			quoteGenerators.add(generator);
		}

		startThreads(quoteGenerators);
	}

	protected void startThreads(List<QuoteGenerator> quoteGenerators) {
		for (QuoteGenerator generator : quoteGenerators) {
			Thread quoteGenThread = new Thread(generator, "QuoteGenerator-" + generator.getExchange());
			quoteGenThread.setDaemon(true);
			quoteGenThread.start();
		}
	}

	public void updateQuote(ExchangeAndCurrencyPair exchangeAndCurrencyPair, Quote q) {
		liveQuotes.put(exchangeAndCurrencyPair, q);
		quoteArrivedCounter.incrementAndGet();
	}

	public Optional<Quote> getLatestQuote(ExchangeAndCurrencyPair p) {
		return Optional.ofNullable(liveQuotes.get(p));
	}

	public boolean hasNewQuote() {
		counterVal = quoteArrivedCounter.get();
		return counterVal > lastCounterVal;
	}

	public void allQuotesProcessed() {
		lastCounterVal = counterVal;
	}

	public void marketPairsUpdated() {
		/*
		 * Force immediate re-evaluation of all quote pairs
		 */
		lastCounterVal = quoteArrivedCounter.get() - 1;
	}

	/* If the quote times are too far apart, we can't trust them */
	public boolean quotesComparable(QuotePair quotePair) {
		Quote longQuote = quotePair.getLongQuote();
		Quote shortQuote = quotePair.getShortQuote();
		long millisBetween = abs(MILLIS.between(shortQuote.getCreationTime(), longQuote.getCreationTime()));
		if (millisBetween > params.maxQuoteTimeDifferenceMillis) {
			if (log.isDebugEnabled())
				log.debug("Time too long between quotes: {} {} / {} {} @ {}", shortQuote.getExchange(),
						shortQuote.getCurrencyPair(), longQuote.getExchange(), longQuote.getCurrencyPair(),
						formatDurationWords(millisBetween, true, true));
			return false;
		}

		return true;
	}

}
