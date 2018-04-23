package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static org.junit.Assert.assertTrue;
import static org.knowm.xchange.currency.Currency.USD;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USD;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import org.junit.Ignore;
import org.junit.Test;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.kraken.dto.trade.KrakenOpenPosition;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;
import com.slickapps.blackbird.util.FormatUtil;

public class KrakenIntegrationTest extends AbstractExchangeIntegrationTest {

	@Override
	protected BlackbirdExchange createExchange() throws IOException {
		Parameters params = createTestParameters();
		return new Kraken(params);
	}

	@Test
	public void testCancelOrder() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + formatCurrency(USD, quote.getAsk()));
		String longOrderId = exchange
				.openLongPosition(BTC_USD, minQuantityBTCUSD, false, quote.getAsk().subtract(new BigDecimal(100)))
				.get();
		System.out.println("Order placed, canceling order...");
		exchange.cancelOrder(BTC_USD, longOrderId).get();
		System.out.println("Order canceled.");
	}

	@Test
	public void testQueryForQuote() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Quote: " + quote);
	}

	@Test
	public void testQueryWallet() throws InterruptedException, ExecutionException {
		Wallet wallet = exchange.queryWallet(false).get();
		System.out.println("Wallet retrieved successfully: " + wallet);
	}

	@Test
	public void testQueryBalance() throws InterruptedException, ExecutionException {
		exchange.queryBalance(USD, false).get();
	}

	@Test
	public void testQueryOrder() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryOrder(BTC_USD, "OIRFV7-A4OZQ-4K5YHO").get());
	}

	@Test
	public void testOpenAndCloseShortPosition() throws InterruptedException, ExecutionException {
		// 0.002 BTC is the minimum order allowed by Kraken
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Bid price is " + FormatUtil.formatCurrency(USD, quote.getBid()));

		String orderId = exchange.openShortPosition(BTC_USD, minQuantityBTCUSD, false, quote.getBid()).get();
		System.out.println("Opened short position at " + LocalDateTime.now() + ", ID = " + orderId);

		OrderCompletionStatus completionStatus = waitForOrderCompletion(BTC_USD, orderId);
		boolean normalCompletion = completionStatus != null && completionStatus.isComplete();

		if (normalCompletion) {
			System.out.println("Attempting to close position...");
			quote = exchange.queryForQuote(BTC_USD).get();
			System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, quote.getAsk()));
			String longOrderId = exchange.closeShortPosition(BTC_USD, minQuantityBTCUSD, false, quote.getAsk()).get();
			System.out.println("Closed position with order ID " + longOrderId);
		} else if (completionStatus == null) {
			System.out.println("Order not completed or allotted time expired.");
		} else {
			System.out.println("Order didn't complete normally: " + completionStatus);
		}
	}

	@Test
	/*
	 * This leaves open positions so let's disable it unless we need it for manual
	 * testing
	 */
	@Ignore
	public void testOpenShortPosition() throws InterruptedException, ExecutionException {
		// 0.002 BTC is the minimum order allowed by Kraken
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Bid price is " + FormatUtil.formatCurrency(USD, quote.getBid()));

		String orderId = exchange.openShortPosition(BTC_USD, minQuantityBTCUSD, false, quote.getBid()).get();
		System.out.println("Opened short position at " + LocalDateTime.now() + ", ID = " + orderId);

		waitForOrderCompletion(BTC_USD, orderId);
	}

	@Test
	@Ignore // CAREFUL this will close production positions
	public void testRunPositionCleanup() {
		Kraken kraken = (Kraken) exchange;
		// close any open positions up front
		List<String> closePositionOrderIds = kraken.runPositionCleanup(new MarketPairsProvider() {
			@Override
			public ExchangePairsInMarket getPairsInMarket() {
				return new ExchangePairsInMarket();
			}

			@Override
			public SortedSet<ExchangePairAndCurrencyPair> getPairsOutOfMarket() {
				return new TreeSet<ExchangePairAndCurrencyPair>();
			}

		}, System.currentTimeMillis());
		for (String orderId : closePositionOrderIds) {
			waitForOrderCompletion(BTC_USD, orderId);
		}
		Map<String, KrakenOpenPosition> openPositions = kraken.getOpenPositions();
		assertTrue(openPositions.isEmpty());
	}

}
