package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.BigDecimal.ONE;
import static java.time.LocalDateTime.now;
import static org.knowm.xchange.currency.Currency.USD;
import static org.knowm.xchange.currency.Currency.USDT;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USDT;
import static org.knowm.xchange.dto.Order.OrderType.ASK;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;

import org.junit.Ignore;
import org.junit.Test;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.trade.UserTrades;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;
import com.slickapps.blackbird.util.FormatUtil;

public class LiquiIntegrationTest extends AbstractExchangeIntegrationTest {

	@Override
	protected BlackbirdExchange createExchange() throws IOException {
		Parameters params = createTestParameters();
		return new Liqui(params);
	}

	private BigDecimal getMinTotal() {
		return exchange.getOrderMinTotal(BTC_USDT);
	}

	private BigDecimal getSmallQuantity() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		return getSmallQuantity(quote.getAsk());
	}

	private BigDecimal getSmallQuantity(BigDecimal ask) {
		BigDecimal q = getMinTotal().divide(ask, MathContext.DECIMAL32);
		q = exchange.roundQuantityToStepSizeIfNecessary(false, q, BTC_USDT);
		BigDecimal orderMinQuantity = exchange.getOrderMinQuantity(BTC_USDT);
		if (q.compareTo(orderMinQuantity) < 0)
			q = orderMinQuantity;

		return q;
	}

	@Test
	public void testQueryTradeHistoryWithinRate() {
		Liqui l = (Liqui) exchange;
		UserTrades userTrades = l.queryTradeHistoryWithinRate(BTC_USDT);
		System.out.println(userTrades);
	}

	@Test
	public void testCancelOrder() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		System.out.println("Ask price is " + formatCurrency(USD, quote.getAsk()));
		BigDecimal ask = quote.getAsk().subtract(new BigDecimal(100));
		BigDecimal smallQuantity = getSmallQuantity(ask);
		String longOrderId = exchange.openLongPosition(BTC_USDT, smallQuantity, false, ask).get();
		System.out.println("Order placed, canceling order...");
		exchange.cancelOrder(BTC_USDT, longOrderId).get();
		System.out.println("Order canceled.");
	}

	@Test
	public void testQueryForQuote() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryForQuote(BTC_USDT).get());
	}

	@Test
	public void testQueryWallet() throws InterruptedException, ExecutionException {
		Wallet wallet = exchange.queryWallet(false).get();
		System.out.println("Wallet retrieved successfully: " + wallet);
	}

	@Test
	public void testQueryBalance() throws InterruptedException, ExecutionException {
		for (int i = 0; i < 3; i++)
			System.out.println(exchange.queryBalance(USDT, false).get());
	}

	@Test
	public void testQueryOrder() throws InterruptedException, ExecutionException {
		Order order = exchange.queryOrder(BTC_USDT, "246915580").get().get();
		System.out.println(order);
	}

	@Test
	public void testQueryIsOrderFilled() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryOrder(BTC_USDT, "244620839").get().get().getStatus());
	}

	@Test
	public void testQueryLimitPrice() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryLimitPrice(BTC_USDT, getSmallQuantity(), ASK).get());
	}

	@Test
	@Ignore
	public void testOpenAndCloseLongPosition() throws InterruptedException, ExecutionException {
		// 0.01 BTC is the minimum order allowed by exchange
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, quote.getAsk()));

		BigDecimal ask = quote.getAsk().add(ONE);
		BigDecimal smallQuantity = getSmallQuantity(ask);
		String orderId = exchange.openLongPosition(BTC_USDT, smallQuantity, false, ask).get();
		System.out.println("Opened long position at " + now() + ", ID = " + orderId);

		OrderCompletionStatus completionStatus = waitForOrderCompletion(BTC_USDT, orderId);
		boolean normalCompletion = completionStatus != null && completionStatus.isComplete();

		if (normalCompletion) {
			System.out.println("Attempting to close position...");
			quote = exchange.queryForQuote(BTC_USDT).get();
			System.out.println("Bid price is " + formatCurrency(USD, quote.getBid()));
			String longOrderId = exchange
					.closeLongPosition(BTC_USDT, smallQuantity, false, quote.getBid().subtract(ONE)).get();
			completionStatus = waitForOrderCompletion(BTC_USDT, orderId);
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
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		BigDecimal ask = quote.getAsk();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, ask));

		String orderId = exchange.openLongPosition(BTC_USDT, getSmallQuantity(ask), false, ask).get();
		System.out.println("Opened long position at " + LocalDateTime.now() + ", ID = " + orderId);

		waitForOrderCompletion(BTC_USDT, orderId);
	}

}
