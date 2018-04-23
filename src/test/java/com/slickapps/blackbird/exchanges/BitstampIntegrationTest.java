package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.BigDecimal.ONE;
import static java.math.MathContext.DECIMAL32;
import static java.time.LocalDateTime.now;
import static org.knowm.xchange.currency.Currency.USD;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USD;
import static org.knowm.xchange.currency.CurrencyPair.ETH_USD;
import static org.knowm.xchange.dto.Order.OrderType.ASK;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.junit.Test;
import org.knowm.xchange.bitstamp.dto.trade.BitstampOrderStatusResponse;
import org.knowm.xchange.bitstamp.dto.trade.BitstampOrderTransaction;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.dto.trade.UserTrades;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;
import com.slickapps.blackbird.util.FormatUtil;

public class BitstampIntegrationTest extends AbstractExchangeIntegrationTest {

	@Override
	protected BlackbirdExchange createExchange() throws IOException {
		Parameters params = createTestParameters();
		return new Bitstamp(params);
	}

	@Test
	public void testCancelOrder() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + formatCurrency(USD, quote.getAsk()));
		String longOrderId = exchange.openLongPosition(BTC_USD, getMinQuantityForPrice(quote.getAsk()), false,
				quote.getAsk().subtract(new BigDecimal(100))).get();
		System.out.println("Order placed, canceling order...");
		exchange.cancelOrder(BTC_USD, longOrderId).get();
		System.out.println("Order canceled.");
	}

	private BigDecimal getMinQuantityForPrice(BigDecimal quoteAmount) {
		Bitstamp bitstamp = (Bitstamp) exchange;
		BigDecimal unroundedQuantity = bitstamp.getOrderMinTotal(BTC_USD)
				/*
				 * when we sell we're gonna sell for slightly less, so our sell quantity will be
				 * less than the min. since the loss is only a couple cents after the sell let's
				 * just double our order quantity here.
				 */
				.multiply(new BigDecimal(2)) //
				.divide(quoteAmount, DECIMAL32);
		return bitstamp.roundQuantityToStepSizeIfNecessary(true, unroundedQuantity, BTC_USD);
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
		Order order = exchange.queryOrder(ETH_USD, "849896579").get().get();
		System.out.println(order);
	}

	@Test
	public void testQueryOrderStatus() throws InterruptedException, ExecutionException, IOException {
		BitstampOrderStatusResponse order = ((Bitstamp) exchange).queryOrderStatus("849896579");
		System.out.println(ToStringBuilder.reflectionToString(order));
		BitstampOrderTransaction[] transactions = order.getTransactions();
		for (BitstampOrderTransaction t : transactions)
			System.out.println(ToStringBuilder.reflectionToString(t));
	}

	@Test
	public void testQueryTradeHistory() throws InterruptedException, ExecutionException, IOException {
		UserTrades order = ((Bitstamp) exchange).queryTradeHistory(CurrencyPair.ETH_USD);
		System.out.println(ToStringBuilder.reflectionToString(order));
		List<UserTrade> trades = order.getUserTrades();
		for (UserTrade t : trades)
			System.out.println(ToStringBuilder.reflectionToString(t));
	}

	@Test
	public void testQueryLimitPrice() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryLimitPrice(BTC_USD, new BigDecimal(0.01), ASK).get());
	}

	@Test
	public void testOpenAndCloseLongPosition() throws InterruptedException, ExecutionException {
		// 0.01 BTC is the minimum order allowed by exchange
		Quote quote = exchange.queryForQuote(BTC_USD).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USD, quote.getAsk()));

		BigDecimal quantity = getMinQuantityForPrice(quote.getAsk());
		String orderId = exchange.openLongPosition(BTC_USD, quantity, false, quote.getAsk().add(ONE)).get();
		System.out.println("Opened long position at " + now() + ", ID = " + orderId);

		OrderCompletionStatus completionStatus = waitForOrderCompletion(BTC_USD, orderId);
		boolean normalCompletion = completionStatus != null && completionStatus.isComplete();

		if (normalCompletion) {
			System.out.println("Attempting to close position...");
			quote = exchange.queryForQuote(BTC_USD).get();
			System.out.println("Bid price is " + formatCurrency(USD, quote.getBid()));
			String longOrderId = exchange.closeLongPosition(BTC_USD, quantity, false, quote.getBid().subtract(ONE))
					.get();
			completionStatus = waitForOrderCompletion(BTC_USD, longOrderId);
			System.out.println("Closed position with order ID " + longOrderId);
		} else if (completionStatus == null) {
			System.out.println("Order not completed or allotted time expired.");
		} else {
			System.out.println("Order didn't complete normally: " + completionStatus);
		}
	}

}
