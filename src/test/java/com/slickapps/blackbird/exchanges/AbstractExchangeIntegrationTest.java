package com.slickapps.blackbird.exchanges;

import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.getFromOrderStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;

import org.junit.Before;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;

import com.slickapps.blackbird.AbstractBlackbirdTest;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;

public abstract class AbstractExchangeIntegrationTest extends AbstractBlackbirdTest {

	protected BlackbirdExchange exchange;
	protected BigDecimal minQuantityBTCUSD;

	@Before
	public void initExchange() throws IOException {
		exchange = createExchange();
		if (!exchange.isEnabled())
			throw new RuntimeException(
					"Please set the exchange " + exchange.getName() + " to enabled in blackbird-test.conf");
		minQuantityBTCUSD = exchange.getOrderMinQuantity(CurrencyPair.BTC_USD);
	}

	protected abstract BlackbirdExchange createExchange() throws IOException;

	protected OrderCompletionStatus waitForOrderCompletion(CurrencyPair currencyPair, String orderId) {
		OrderCompletionStatus completionStatus = null;
		for (int i = 0; (completionStatus == null || !completionStatus.isComplete()) && i < 50; i++) {
			try {
				System.out.println("Awaiting order completion... (" + (i + 1) + "/50)");
				Optional<Order> orderOpt = exchange.queryOrder(currencyPair, orderId).get();
				if (!orderOpt.isPresent())
					break;

				completionStatus = getFromOrderStatus(orderOpt.get().getStatus());
				if (completionStatus == null || !completionStatus.isComplete())
					Thread.sleep(5000);
			} catch (Exception ignored) {
			}
		}

		if (completionStatus == null || !completionStatus.isComplete()) {
			System.out.println("Order ID " + orderId + " not completed.");
		} else {
			System.out.println("Order ID " + orderId + " completed with status " + completionStatus + ".");
		}
		return completionStatus;
	}

}
