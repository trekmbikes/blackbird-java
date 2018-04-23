package com.slickapps.blackbird.processes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.Main;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.BlackbirdEventListener;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.service.QuoteService;
import com.slickapps.blackbird.util.exception.ExceptionUtil;

/**
 * This class returns quotes of the various currency pairs supported by an
 * Exchange (in a round-robin fashion).
 * 
 * @author barrycon
 *
 */
public class QuoteGenerator implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(QuoteGenerator.class);

	private static final int MAX_EXCEPTIONS_BEFORE_DISABLE = 5;

	protected QuoteService quoteService;
	protected BlackbirdExchange exchange;
	protected EventListenerProvider eventListenerProvider;

	protected List<CurrencyPair> uniqueCurrencyPairs = new ArrayList<>();
	private int currencyPairIndex = 0;

	public QuoteGenerator(QuoteService quoteService, BlackbirdExchange exchange,
			EventListenerProvider eventListenerProvider) {
		this.quoteService = quoteService;
		this.exchange = exchange;
		this.eventListenerProvider = eventListenerProvider;

		this.uniqueCurrencyPairs.addAll(exchange.getCurrencyPairsForLongPositions());
		this.uniqueCurrencyPairs.addAll(exchange.getCurrencyPairsForShortPositions());

		if (this.uniqueCurrencyPairs.isEmpty())
			throw new IllegalArgumentException("No supported currencies defined for " + exchange);
	}

	@Override
	public void run() {
		try {
			int numExceptions = 0;

			while (Main.stillRunning) {
				try {
					generateNextQuote();
				} catch (Exception e) {
					if (ExceptionUtil.isRetryable(exchange, e)) {
						numExceptions++;

						if (numExceptions < MAX_EXCEPTIONS_BEFORE_DISABLE) {
							log.warn("Couldn't retrieve quote from exchange {}, trying again...", exchange);
							log.debug("Full quote retrieval exception:", e);
						} else {
							numExceptions = 0;
							ExceptionUtil.disableExchange(exchange);
						}
					} else {
						log.error("Encountered an exception while generating a quote for " + exchange, e);
						Thread.sleep(10000);
					}
				}
			}
		} catch (InterruptedException e) {
			log.debug("{} interrupted, exiting...", getClass().getSimpleName());
		}
	}

	public final void generateNextQuote() throws InterruptedException, ExecutionException {
		if (exchange.isDisabledTemporarilyOrNeedsWalletPopulation()) {
			Thread.sleep(10000);
			return;
		}

		Collection<Quote> quoteList = getQuotes();

		for (Quote newQuote : quoteList) {
			quoteService.updateQuote(newQuote.getExchangeAndCurrencyPair(), newQuote);
			for (BlackbirdEventListener l : eventListenerProvider.getEventListeners())
				l.quoteReceived(newQuote);
		}
	}

	protected Collection<Quote> getQuotes() throws InterruptedException, ExecutionException {
		/* get the value for the current currency */
		CurrencyPair currencyPair = uniqueCurrencyPairs.get(currencyPairIndex);
		/* set the index for next time */
		currencyPairIndex = (currencyPairIndex + 1) % uniqueCurrencyPairs.size();

		CompletableFuture<Quote> quoteFuture = exchange.queryForQuote(currencyPair);
		Quote q = quoteFuture.get();
		List<Quote> quoteList = Arrays.asList(q);
		return quoteList;
	}

	public BlackbirdExchange getExchange() {
		return exchange;
	}

}
