package com.slickapps.blackbird.processes;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.dto.account.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.Main;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.service.BalanceService;

/**
 * A daemon thread which periodically polls the wallets at each exchange to
 * ensure their caches are updated
 * 
 * @author barrycon
 *
 */
public class ExchangeWalletPoller implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(ExchangeWalletPoller.class);

	/* Every 5 minutes */
	private static final long SLEEP_TIME_BETWEEN_REQUESTS = 300000;

	private Parameters params;
	private List<BlackbirdExchange> exchanges;
	private ExchangePairsInMarket exchangePairsInMarket;

	public ExchangeWalletPoller(Parameters params, List<BlackbirdExchange> exchanges,
			ExchangePairsInMarket exchangePairsInMarket) {
		this.params = params;
		this.exchanges = exchanges;
		this.exchangePairsInMarket = exchangePairsInMarket;
	}

	@Override
	public void run() {
		while (Main.stillRunning) {
			try {
				for (BlackbirdExchange exchange : exchanges) {
					if (!exchange.isEnabled() || exchange.isDisabledTemporarily())
						continue;

					try {
						boolean isFirstTime = !exchange.isWalletPopulated();

						CompletableFuture<Wallet> future = exchange.queryWallet(true);
						if (isFirstTime) {
							Wallet wallet = future.get();
							BalanceService.performInitialBalanceValidation(params, exchangePairsInMarket, exchange,
									wallet);
						}
					} catch (Exception ignored) {
						log.warn("Encountered an exception while polling the balance at " + exchange + ", ignoring...",
								ignored);
					}
				}

				Thread.sleep(SLEEP_TIME_BETWEEN_REQUESTS);
			} catch (InterruptedException e) {
				log.debug("{} interrupted, exiting", getClass().getSimpleName());
				return;
			}
		}
	}

	public static ExchangeWalletPoller initAndStart(Parameters params, List<BlackbirdExchange> exchanges,
			ExchangePairsInMarket exchangePairsInMarket) {
		log.info("Starting balance poller...");
		ExchangeWalletPoller exchangeWalletPoller = new ExchangeWalletPoller(params, exchanges, exchangePairsInMarket);
		Thread quoteGenThread = new Thread(exchangeWalletPoller, "BalancePoller");
		quoteGenThread.setDaemon(true);
		quoteGenThread.start();
		return exchangeWalletPoller;
	}

}