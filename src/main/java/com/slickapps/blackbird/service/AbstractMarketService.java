package com.slickapps.blackbird.service;

import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.OrderPair;
import com.slickapps.blackbird.model.orderCompletion.OrderRollbackType;

public class AbstractMarketService {
	private static final Logger log = LoggerFactory.getLogger(AbstractMarketService.class);

	/**
	 * Cancels the specified longOrderId, and reverts any amount already processed
	 * by that order. Since we're reverting a long order, we will be selling back
	 * some amount of our base currency in exchange for our counter currency. The
	 * revert is performed by placing an order at market price, which we assume will
	 * happen instantly. The rollbackType determines whether we sell back the
	 * cumulative amount purchased to date for a buy order (i.e. to keep ourselves
	 * out of the market, if we're in the process of entering it), or the remaining
	 * amount unfilled for a sell order (i.e. to get ourselves out of the market as
	 * quickly as possible, if we're in the process of exiting it).
	 * 
	 * @param longExchange
	 * @param currencyPair
	 * @param longOrderId
	 * @param rollbackType
	 * @return The new revert order ID placed, or null if no reversion was necessary
	 */
	protected CompletableFuture<OrderPair> cancelOrRevertLongOrder(BlackbirdExchange longExchange,
			CurrencyPair currencyPair, String longOrderId, OrderRollbackType rollbackType) {
		return longExchange.cancelOrRevertLongOrder(currencyPair, longOrderId, rollbackType);
	}

	protected CompletableFuture<OrderPair> cancelOrRevertShortOrder(BlackbirdExchange shortExchange,
			CurrencyPair currencyPair, String shortOrderId, OrderRollbackType rollbackType) {
		return shortExchange.cancelOrRevertShortOrder(currencyPair, shortOrderId, rollbackType);
	}

}
