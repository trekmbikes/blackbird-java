package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static com.slickapps.blackbird.util.FormatUtil.getQuantityFormatter;
import static org.knowm.xchange.dto.Order.OrderType.ASK;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.okcoin.OkCoinExchange;

import com.slickapps.blackbird.model.Parameters;

public class OkCoin extends AbstractBlackbirdExchange {

	public OkCoin(Parameters params) {
		initialize(params);
	}

	@Override
	protected Exchange createExchangeInstance() {
		return new OkCoinExchange();
	}

	@Override
	public CompletableFuture<String> openShortPositionImp(CurrencyPair currencyPair, BigDecimal quantity,
			boolean useMarketOrder, BigDecimal limitPriceOverride) {
		return callAsyncWithRetry(() -> {
			log.info("Trying to open a short position", getQuantityFormatter().format(quantity),
					formatCurrency(currencyPair.counter, limitPriceOverride));

			if (params.demoMode)
				return placeDummyOrder(quantity, limitPriceOverride, ASK);

			// TODO
			// Unlike Bitfinex and Poloniex, on OKCoin the borrowing phase has to be done
			// as a separated step before being able to short sell.
			// Here are the steps:
			// Step | Function
			// -----------------------------------------|----------------------
			// 1. ask to borrow bitcoins | borrowBtc(amount) FIXME bug "10007: Signature
			// does not match"
			// 2. sell the bitcoins on the market | sendShortOrder("sell")
			// 3. <wait for the spread to close> |
			// 4. buy back the bitcoins on the market | sendShortOrder("buy")
			// 5. repay the bitcoins to the lender | repayBtc(borrowId)
			return "0";
		});
	}

	// @Override
	// public CompletableFuture<Wallet> queryWallet(boolean allowCache) {
	// return callAsyncWithinRateLimit(new Supplier<Wallet>() {
	// @Override
	// public Wallet get() {
	// return runWithExceptionHandling(() -> {
	// OkCoinAccountService accountService = (OkCoinAccountService)
	// exchange.getAccountService();
	// OkCoinUserInfo userInfo = accountService.getUserInfo();
	// OkCoinInfo info = userInfo.getInfo();
	// OkCoinFunds funds = info.getFunds();
	// Map<String, BigDecimal> free = funds.getFree();
	// BigDecimal btcFree = free.get(c.getCurrencyCode());
	// return btcFree;
	// });
	// }
	// });
	// }

}
