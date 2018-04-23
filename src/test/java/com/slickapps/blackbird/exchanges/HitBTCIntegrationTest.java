package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.BigDecimal.ONE;
import static java.time.LocalDateTime.now;
import static org.knowm.xchange.currency.Currency.USD;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USD;
import static org.knowm.xchange.dto.Order.OrderType.ASK;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;

import org.junit.Ignore;
import org.junit.Test;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Wallet;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;
import com.slickapps.blackbird.util.FormatUtil;

public class HitBTCIntegrationTest extends AbstractExchangeIntegrationTest {

	private static final BigDecimal MIN_ORDER_QUANTITY = new BigDecimal(0.01);

	@Override
	protected BlackbirdExchange createExchange() throws IOException {
		Parameters params = createTestParameters();
		return new HitBTC(params);
	}

	@Test
	public void testCancelOrder() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + formatCurrency(USD, quote.getAsk()));
		String longOrderId = exchange
				.openLongPosition(BTC_USD, MIN_ORDER_QUANTITY, false, quote.getAsk().subtract(new BigDecimal(100)))
				.get();
		System.out.println("Order placed, canceling order...");
		exchange.cancelOrder(BTC_USD, longOrderId).get();
		System.out.println("Order canceled.");
	}

	@Test
	public void testQueryForQuote() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryForQuote(BTC_USD).get());
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
		Order order = exchange.queryOrder(BTC_USD, "faa68922c80a468e94ba88d76581a3b2").get().get();
		System.out.println(order);
	}

	@Test
	public void testQueryIsOrderFilled() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryOrder(BTC_USD, "2f6aa072bec20d95f0b80d60bed3b74a").get().get().getStatus());
	}

	@Test
	public void testQueryLimitPrice() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryLimitPrice(BTC_USD, MIN_ORDER_QUANTITY, ASK).get());
	}

	@Test
	public void testOpenAndCloseLongPosition() throws InterruptedException, ExecutionException {
		// 0.01 BTC is the minimum order allowed by exchange
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, quote.getAsk()));

		String orderId = exchange.openLongPosition(BTC_USD, MIN_ORDER_QUANTITY, false, quote.getAsk().add(ONE)).get();
		System.out.println("Opened long position at " + now() + ", ID = " + orderId);

		OrderCompletionStatus completionStatus = waitForOrderCompletion(BTC_USD, orderId);
		boolean normalCompletion = completionStatus != null && completionStatus.isComplete();

		if (normalCompletion) {
			System.out.println("Attempting to close position...");
			quote = exchange.queryForQuote(BTC_USD).get();
			System.out.println("Bid price is " + formatCurrency(USD, quote.getBid()));
			String longOrderId = exchange
					.closeLongPosition(BTC_USD, MIN_ORDER_QUANTITY, false, quote.getBid().subtract(ONE)).get();
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
	public void testOpenLongPosition() throws InterruptedException, ExecutionException {
		// 0.002 BTC is the minimum order allowed by exchange
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, quote.getAsk()));

		String orderId = exchange.openLongPosition(BTC_USD, MIN_ORDER_QUANTITY, false, quote.getAsk()).get();
		System.out.println("Opened long position at " + LocalDateTime.now() + ", ID = " + orderId);

		waitForOrderCompletion(BTC_USD, orderId);
	}

}
