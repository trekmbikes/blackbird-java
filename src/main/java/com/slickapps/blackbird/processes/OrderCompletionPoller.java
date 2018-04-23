package com.slickapps.blackbird.processes;

import static com.slickapps.blackbird.Main.stillRunning;
import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.TIME_EXPIRED;
import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.UNRECOVERABLE_EXCEPTION;
import static com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus.getFromOrderStatus;
import static com.slickapps.blackbird.util.exception.ExceptionUtil.disableExchange;
import static com.slickapps.blackbird.util.exception.ExceptionUtil.isRetryable;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;
import static org.apache.commons.lang3.StringUtils.left;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletion;
import com.slickapps.blackbird.model.orderCompletion.OrderCompletionStatus;

/**
 * A non-permanent thread which periodically polls the status of an order for
 * completion, and activates a callback after either the order is completed or
 * the maximum time allotted expires.
 * 
 * @author barrycon
 *
 */
public class OrderCompletionPoller implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(OrderCompletionPoller.class);

	/*
	 * This is in addition to the standard rate limit imposed by each Exchange, so
	 * we play nicely with other parallel requests coming in to the exchange
	 */
	private static final long SLEEP_TIME_BETWEEN_REQUESTS = 4000;

	/*
	 * Ensure that if we're already monitoring orders for a specific
	 * ExchangePairInMarket, we don't kick off another instance of this class. The
	 * key is the ID of the ExchangePairInMarket.
	 */
	private static final ConcurrentHashMap<Integer, Boolean> PAIR_IDS_BEING_POLLED = new ConcurrentHashMap<>();

	private BlackbirdExchange exchange;
	private String orderId;
	private CurrencyPair orderCurrencyPair;
	private long maxExecutionTime;
	private Consumer<OrderCompletion> callback;

	private OrderCompletionPoller(BlackbirdExchange e, String orderId, CurrencyPair orderCurrencyPair,
			long maxExecutionTime, Consumer<OrderCompletion> callback) {
		this.exchange = e;
		this.orderId = orderId;
		this.orderCurrencyPair = orderCurrencyPair;
		this.maxExecutionTime = maxExecutionTime;
		this.callback = callback;
	}

	@Override
	public void run() {
		long startTime = currentTimeMillis();

		try {
			/*
			 * Define "complete" as having arrived at a decision about the order based on a
			 * response from the exchange. Complete statuses may be order canceled, order
			 * filled, stopped, etc. - basically any completion status other than where the
			 * maxExecutionTime is exceeded (leading to a status of TIME_EXPIRED) or where
			 * we encountered an unrecoverable exception (leading to a status of
			 * UNRECOVERABLE_EXCEPTION).
			 * 
			 * Our status calls to the exchange (such as queryOrder) all have retry logic
			 * within them. If these methods throw an exception, it means they already
			 * retried a few times. If the exception they threw satisfies
			 * ExceptionUtil.isRetryable(), it means we need to disable our exchange
			 * temporarily and allow this same OrderCompletionPoller to loop until the
			 * exchange is back online. If the methods throw a more permanent exception that
			 * isn't retryable, we'll return with an exception status.
			 * 
			 * The overall maxExecutionTime specified for this OrderCompletionPoller takes
			 * precedence over the exchange going offline; even if the exchange is offline,
			 * if we exceed our maxExecutionTime we'll return with a status of TIME_EXPIRED.
			 */

			Order order = null;
			OrderCompletionStatus ocs = null;

			while (order == null || ocs == null) {
				/* If our overall program running flag was stopped, get outta here */
				if (!stillRunning)
					return;

				sleep(SLEEP_TIME_BETWEEN_REQUESTS);

				if (currentTimeMillis() > startTime + maxExecutionTime) {
					log.info("Order {} on {} was not filled within the maximum allowed time.", orderId, exchange);
					callback.accept(new OrderCompletion(exchange, TIME_EXPIRED, orderId, null, null));
					return;
				}

				if (exchange.isDisabledTemporarilyOrNeedsWalletPopulation())
					continue;

				try {
					Optional<OrderStatus> tempOrderOpt = exchange.queryOrderStatus(orderCurrencyPair, orderId).get();
					if (!tempOrderOpt.isPresent()) {
						log.warn("Unexpectedly got a null order status for order ID {} on {}; trying again...", orderId,
								exchange);
						continue;
					}

					OrderStatus tempOrder = tempOrderOpt.get();
					OrderCompletionStatus tempOCS = getFromOrderStatus(tempOrder);
					
					if (tempOCS != null && tempOCS.isComplete()) {
						/*
						 * Now that our order was filled, query the complete order including aggregate
						 * fields
						 */
						Optional<Order> orderOpt = exchange.queryOrder(orderCurrencyPair, orderId).get();
						if (orderOpt.isPresent())
							order = orderOpt.get();

						ocs = tempOCS;
					} else {
						log.info("Order ID {} on {} still open...", orderId, exchange);
					}
				} catch (ExecutionException e) {
					if (isRetryable(exchange, e)) {
						log.error("Error attempting to check for order completion for exchange " + exchange
								+ ", order ID " + orderId + ", disabling exchange temporarily...", e);
						disableExchange(exchange);
					} else {
						callback.accept(
								new OrderCompletion(exchange, UNRECOVERABLE_EXCEPTION, orderId, null, e.getCause()));
						return;
					}
				}
			}

			/*
			 * Moving this block outside the loop so we don't wrap our accept() call with a
			 * try/catch for an ExecutionException
			 */
			log.info("Order ID {} on {} is completed with a status of {}.", orderId, exchange, ocs);
			callback.accept(new OrderCompletion(exchange, ocs, orderId, order, null));
		} catch (InterruptedException e) {
			log.debug("{} interrupted with exchange {}, order ID {}, exiting", getClass().getSimpleName(), exchange,
					orderId);
		}
	}

	/**
	 * For both the long and short orders in the specified epim, this method checks
	 * to see if either has not yet been completed (per the orderIdXFilled field).
	 * For any that haven't, it spawns a thread which polls the appropriate
	 * exchange, waiting for the order to be filled. Once complete, it sets the
	 * orderIdXFilled boolean field to true in the specified epim. Once both order
	 * have been filled (either due to the orderIdXFilled field already being true
	 * or due to the exchange reporting it was just filled), the specified
	 * ordersFilledSuccessfully Consumer is activated with a status of "true". If
	 * the duration of polling exceeds the orderCompletionMaxExecutionMillis field
	 * in the parameters, we attempt to cancel the other order (ignoring any errors
	 * if we can't cancel it) and activate the ordersFilledSuccessfully specified
	 * Consumer with a value of false.
	 * 
	 * @param epim
	 * @return True if new pollers were started to poll for order completion; false
	 *         if no pollers were started due to other pollers already processing
	 *         this ExchangePairInMarket
	 */
	public static boolean startPollers(ExchangePairInMarket epim, boolean entryNotExit,
			long orderCompletionMaxExecutionMillis, Consumer<OrderCompletion[]> orderCompletionHandler) {
		/*
		 * If we already had an entry in the map, this would return the previous key, so
		 * we'd return; otherwise, register our new pollers here and remove them when
		 * finished before executing the callback
		 */
		if (PAIR_IDS_BEING_POLLED.putIfAbsent(epim.getId(), true) != null)
			return false;

		BlackbirdExchange longExchange = epim.getLongExchange();
		String longOrderId = entryNotExit ? epim.getEntryLongOrderId() : epim.getExitLongOrderId();
		CurrencyPair longCurrencyPair = epim.getLongCurrencyPair();

		BlackbirdExchange shortExchange = epim.getShortExchange();
		String shortOrderId = entryNotExit ? epim.getEntryShortOrderId() : epim.getExitShortOrderId();
		CurrencyPair shortCurrencyPair = epim.getShortCurrencyPair();

		if (longOrderId == null || shortOrderId == null)
			throw new IllegalStateException("Both the long order and short orders for "
					+ (entryNotExit ? "entry" : "exit") + " must be placed before starting order completion pollers.");

		/* [0] = the long thread, [1] = the short thread */
		Thread[] orderCompletionThreads = new Thread[2];

		OrderCompletion[] completions = new OrderCompletion[2];

		OrderCompletionPoller longOrderCompletionPoller = new OrderCompletionPoller(longExchange, longOrderId,
				longCurrencyPair, orderCompletionMaxExecutionMillis, orderCompletion -> {
					completions[0] = orderCompletion;
					if (completions[1] != null) {
						try {
							orderCompletionHandler.accept(completions);
						} finally {
							PAIR_IDS_BEING_POLLED.remove(epim.getId());
						}
					}
				});

		OrderCompletionPoller shortOrderCompletionPoller = new OrderCompletionPoller(shortExchange, shortOrderId,
				shortCurrencyPair, orderCompletionMaxExecutionMillis, orderCompletion -> {
					completions[1] = orderCompletion;
					if (completions[0] != null)
						try {
							orderCompletionHandler.accept(completions);
						} finally {
							PAIR_IDS_BEING_POLLED.remove(epim.getId());
						}
				});

		String threadPrefix = entryNotExit ? "EntryOrderCompletionPoller-" : "ExitOrderCompletionPoller-";

		orderCompletionThreads[0] = new Thread(longOrderCompletionPoller);
		orderCompletionThreads[0].setName("Long" + threadPrefix + longExchange.getName() + "-" + left(longOrderId, 10));
		orderCompletionThreads[0].start();

		orderCompletionThreads[1] = new Thread(shortOrderCompletionPoller);
		orderCompletionThreads[1]
				.setName("Short" + threadPrefix + shortExchange.getName() + "-" + left(shortOrderId, 10));
		orderCompletionThreads[1].start();

		return true;
	}

}
