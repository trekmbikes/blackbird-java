package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static java.math.BigDecimal.TEN;
import static java.math.MathContext.DECIMAL32;
import static java.time.LocalDateTime.now;
import static org.knowm.xchange.currency.Currency.BTC;
import static org.knowm.xchange.currency.Currency.USDT;
import static org.knowm.xchange.currency.CurrencyPair.BTC_USDT;
import static org.knowm.xchange.dto.Order.OrderType.ASK;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.junit.Ignore;
import org.junit.Test;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.UserTrades;

import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;
import com.slickapps.blackbird.util.FormatUtil;

public class BinanceIntegrationTest extends AbstractExchangeIntegrationTest {

	@Override
	protected BlackbirdExchange createExchange() throws IOException {
		Parameters params = createTestParameters();
		return new Binance(params);
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
		System.out.println(exchange.queryBalance(USDT, false).get());
	}

	@Test
	public void testQueryAllOrders() throws InterruptedException, ExecutionException {
		List<LimitOrder> orders = ((Binance) exchange).queryAllOrders(new CurrencyPair("BTC/USDT"), Optional.empty())
				.get();
		System.out.println(orders);
	}

	@Test
	public void testQueryTradeHistory() throws InterruptedException, ExecutionException {
		UserTrades trades = ((Binance) exchange).queryTradeHistory(new CurrencyPair("BTC/USDT")).get();
		System.out.println(trades);
	}

	@Test
	public void testQueryOrder() throws InterruptedException, ExecutionException {
		Order order = exchange.queryOrder(new CurrencyPair("BTC/USDT"), "39416180").get().get();
		System.out.println(order);
	}

	@Test
	public void testQueryLimitPrice() throws InterruptedException, ExecutionException {
		System.out.println(exchange.queryLimitPrice(BTC_USDT, exchange.getOrderMinQuantity(BTC_USDT), ASK).get());
	}

	@Test
	public void testCancelOrder() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		System.out.println("Ask price is " + formatCurrency(USDT, quote.getAsk()));
		BigDecimal quantity = exchange.getOrderMinTotal(BTC_USDT).divide(quote.getAsk().subtract(new BigDecimal(100)),
				DECIMAL32);
		quantity = exchange.roundQuantityToStepSizeIfNecessary(false, quantity, BTC_USDT);

		String longOrderId = exchange
				.openLongPosition(BTC_USDT, quantity, false, quote.getAsk().subtract(new BigDecimal(100))).get();
		System.out.println("Order placed, canceling order...");
		exchange.cancelOrder(BTC_USDT, longOrderId).get();
		System.out.println("Order canceled.");
	}

	@Test
	@Ignore
	public void testOpenAndCloseLongPosition() throws InterruptedException, ExecutionException {
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USDT, quote.getAsk()));
		System.out.println("Bid price is " + FormatUtil.formatCurrency(USDT, quote.getBid()));

		BigDecimal highBuyOffer = quote.getAsk().add(TEN);
		highBuyOffer = exchange.roundPriceToStepSizeIfNecessary(true, highBuyOffer, BTC_USDT);

		BigDecimal lowSellOffer = quote.getBid().subtract(TEN);
		lowSellOffer = exchange.roundPriceToStepSizeIfNecessary(true, lowSellOffer, BTC_USDT);

		BigDecimal orderMinTotal = exchange.getOrderMinTotal(BTC_USDT);
		BigDecimal buyQuantity = orderMinTotal.divide(highBuyOffer, DECIMAL32).multiply(new BigDecimal(1.2));
		buyQuantity = exchange.roundQuantityToStepSizeIfNecessary(false, buyQuantity, BTC_USDT).stripTrailingZeros();

		BigDecimal expense = (highBuyOffer.subtract(lowSellOffer).multiply(buyQuantity));
		System.out.println("Approx expense to run unit test: " + formatCurrency(USDT, expense));
		// if (System.currentTimeMillis() > 0)
		// return;

		String buyOrderId = exchange.openLongPosition(BTC_USDT, buyQuantity, false, highBuyOffer).get();
		System.out.println(
				"Opened long position at " + now() + ", ID = " + buyOrderId + ", qty requested = " + buyQuantity);

		OrderCompletionStatus completionStatus = waitForOrderCompletion(BTC_USDT, buyOrderId);
		boolean normalCompletion = completionStatus != null && completionStatus.isComplete();

		if (normalCompletion) {
			System.out.println("Querying order...");
			Order order = exchange.queryOrder(BTC_USDT, buyOrderId).get().get();
			System.out.println(order);
			BigDecimal cumulativeAmount = order.getCumulativeAmount();
			System.out.println("Amount filled: " + formatCurrency(BTC, cumulativeAmount));

			System.out.println("Attempting to close position...");
			quote = exchange.queryForQuote(BTC_USDT).get();
			System.out.println("Bid price is " + formatCurrency(USDT, quote.getBid()));
			cumulativeAmount = exchange.roundQuantityToStepSizeIfNecessary(false, cumulativeAmount, BTC_USDT);
			String sellOrderId = exchange.closeLongPosition(BTC_USDT, cumulativeAmount, false, lowSellOffer).get();
			completionStatus = waitForOrderCompletion(BTC_USDT, sellOrderId);
			System.out.println("Closed position with order ID " + sellOrderId + ": " + completionStatus);
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
		Quote quote = exchange.queryForQuote(BTC_USDT).get();
		System.out.println("Ask price is " + FormatUtil.formatCurrency(USDT, quote.getAsk()));

		String orderId = exchange
				.openLongPosition(BTC_USDT, exchange.getOrderMinQuantity(BTC_USDT), false, quote.getAsk()).get();
		System.out.println("Opened long position at " + LocalDateTime.now() + ", ID = " + orderId);

		waitForOrderCompletion(BTC_USDT, orderId);
	}

}
