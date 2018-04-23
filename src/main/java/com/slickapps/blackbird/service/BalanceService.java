package com.slickapps.blackbird.service;

import static com.slickapps.blackbird.Main.NL;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.util.exception.ExceptionUtil;

public class BalanceService {
	private static final Logger log = LoggerFactory.getLogger(BalanceService.class);

	private Parameters params;

	public BalanceService(Parameters params) {
		this.params = params;
	}

	/*
	 * Gets the balances from every exchange
	 */
	public void populateAndValidateBalances(Collection<BlackbirdExchange> exchanges,
			ExchangePairsInMarket exchangePairsInMarket) throws Exception {
		log.info(NL + "[ Current balances ]");
		Map<BlackbirdExchange, Wallet> balanceMap = new HashMap<>();

		Map<BlackbirdExchange, CompletableFuture<Wallet>> futures = new HashMap<>();

		for (BlackbirdExchange exchange : exchanges) {
			CompletableFuture<Wallet> future = exchange.queryWallet(false);
			futures.put(exchange, future);
		}

		for (Entry<BlackbirdExchange, CompletableFuture<Wallet>> entry : futures.entrySet()) {
			BlackbirdExchange exchange = entry.getKey();
			CompletableFuture<Wallet> future = entry.getValue();

			try {
				Wallet wallet = future.get();
				balanceMap.put(exchange, wallet);
			} catch (ExecutionException e) {
				if (ExceptionUtil.isRetryable(exchange, e))
					ExceptionUtil.disableExchange(exchange);
			}
		}

		/* log balances */
		for (BlackbirdExchange e : exchanges) {
			if (e.isDisabledTemporarilyOrNeedsWalletPopulation())
				continue;

			Set<Currency> uniqueCurrencies = new HashSet<>();
			for (CurrencyPair currencyPair : e.getCombinedCurrencyPairs()) {
				uniqueCurrencies.add(currencyPair.base);
				uniqueCurrencies.add(currencyPair.counter);
			}

			Wallet wallet = balanceMap.get(e);
			for (Currency c : uniqueCurrencies) {
				BigDecimal available = wallet.getBalance(c).getAvailable();
				if (available.signum() == 1)
					log.info("\t{} {}", e, formatCurrency(c, available));
			}
		}

		/* validate balances */
		for (BlackbirdExchange e : exchanges) {
			if (!e.isEnabled() || e.isDisabledTemporarilyOrNeedsWalletPopulation())
				continue;

			Wallet wallet = balanceMap.get(e);
			performInitialBalanceValidation(params, exchangePairsInMarket, e, wallet);
		}
	}

	public static void performInitialBalanceValidation(Parameters params, ExchangePairsInMarket exchangePairsInMarket,
			BlackbirdExchange e, Wallet wallet) {
		for (CurrencyPair currencyPair : e.getCombinedCurrencyPairs()) {
			if (exchangePairsInMarket.isInMarket(e, currencyPair))
				continue;

			/*
			 * found an exchange/currency pair that aren't in the market; check to make sure
			 * all base (non-USD) balances are zero
			 */
			BigDecimal baseBalance = wallet.getBalance(currencyPair.base).getAvailable();
			BigDecimal maxAllowed = params.getMaxInitialCurrency(currencyPair.base);
			if (baseBalance.compareTo(maxAllowed) == 1) {
				log.error("All " + currencyPair.base + " accounts must be less than "
						+ formatCurrency(currencyPair.base, maxAllowed) + " before starting; the exchange " + e
						+ " has " + formatCurrency(currencyPair.base, baseBalance));
				log.error("Please reduce or remove the " + currencyPair.base + " balance.");
				ExceptionUtil.disableExchange(e);
				e.clearWallet();
				return;
			}
		}
	}

	public BigDecimal getTotalBalance(Collection<BlackbirdExchange> exchanges, Currency c, boolean allowCache) {
		List<CompletableFuture<Balance>> futures = new ArrayList<>(exchanges.size());
		for (BlackbirdExchange e : exchanges) {
			futures.add(e.queryBalance(c, allowCache));
		}
		BigDecimal total = BigDecimal.ZERO;
		for (CompletableFuture<Balance> f : futures) {
			total = total.add(f.join().getAvailable());
		}
		return total;
	}

}
