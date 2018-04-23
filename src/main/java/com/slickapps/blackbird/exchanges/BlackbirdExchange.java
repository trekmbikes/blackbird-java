package com.slickapps.blackbird.exchanges;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.Order.OrderStatus;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.Wallet;

import com.slickapps.blackbird.EventListenerProvider;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.model.OrderPair;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.orderCompletion.OrderRollbackType;
import com.slickapps.blackbird.processes.QuoteGenerator;
import com.slickapps.blackbird.service.QuoteService;

public interface BlackbirdExchange extends Comparable<BlackbirdExchange> {

	CompletableFuture<Quote> queryForQuote(CurrencyPair currencyPair);

	CompletableFuture<List<Quote>> queryForQuotes(List<CurrencyPair> uniqueCurrencyPairs);

	CompletableFuture<Wallet> queryWallet(boolean allowCache);

	CompletableFuture<Balance> queryBalance(Currency currency, boolean allowCache);

	CompletableFuture<Optional<Order>> queryOrder(CurrencyPair currencyPair, String orderId);

	CompletableFuture<Optional<OrderStatus>> queryOrderStatus(CurrencyPair currencyPair, String orderId);

	CompletableFuture<String> openLongPosition(CurrencyPair currencyPair, BigDecimal quantity, boolean useMarketOrder,
			BigDecimal limitPriceOverride);

	/**
	 * @param currencyPair
	 *            The currencyPair of the position to close
	 * @param quantity
	 *            The quantity of the position to close
	 * @param useMarketOrder
	 *            True if we should allow the exchange to set the price upon order
	 *            submission; false to calculate the current limit price needed
	 *            based on the order book (unless a limitPriceOverride is specified)
	 * @param limitPriceOverride
	 *            If useMarketOrder is false, this price will be used and not a
	 *            calculated limit price based on the order books
	 * @return A CompetableFuture which returns the new order ID
	 */
	CompletableFuture<String> closeLongPosition(CurrencyPair currencyPair, BigDecimal quantity, boolean useMarketOrder,
			BigDecimal limitPriceOverride);

	CompletableFuture<String> openShortPosition(CurrencyPair currencyPair, BigDecimal quantity, boolean useMarketOrder,
			BigDecimal limitPriceOverride);

	/**
	 * @param currencyPair
	 *            The currencyPair of the position to close
	 * @param quantity
	 *            The quantity of the position to close
	 * @param useMarketOrder
	 *            True if we should allow the exchange to set the price upon order
	 *            submission; false to calculate the current limit price needed
	 *            based on the order book (unless a limitPriceOverride is specified)
	 * @param limitPriceOverride
	 *            If useMarketOrder is false, this price will be used and not a
	 *            calculated limit price based on the order books
	 * @return A CompetableFuture which returns the new order ID
	 */
	CompletableFuture<String> closeShortPosition(CurrencyPair currencyPair, BigDecimal quantity, boolean useMarketOrder,
			BigDecimal limitPriceOverride);

	CompletableFuture<Boolean> cancelOrder(CurrencyPair currencyPair, String orderId);

	CompletableFuture<BigDecimal> queryLimitPrice(CurrencyPair currencyPair, BigDecimal volume, OrderType orderType);

	String getDbTableName();

	String getName();

	BigDecimal getFeePercentage();

	boolean isShortable();

	List<CurrencyPair> getCurrencyPairsForShortPositions();

	List<CurrencyPair> getCurrencyPairsForLongPositions();

	Set<CurrencyPair> getCombinedCurrencyPairs();

	boolean isEnabled();

	void disableTemporarily();

	boolean isDisabledTemporarily();

	boolean isDisabledTemporarilyOrNeedsWalletPopulation();

	boolean isWalletPopulated();

	void clearWallet();

	BigDecimal getMaxLeveragableAmount(CurrencyPair c, BigDecimal balance);

	BigDecimal getExposureWithMaxLeverage(CurrencyPair c, BigDecimal baseAmount);

	/**
	 * Key = thread name, value = runnable for Thread
	 * 
	 * @param exchangePairsInMarket
	 * @return
	 */
	Map<String, Runnable> getBackgroundJobs(MarketPairsProvider provider);

	BigDecimal getOrderMinQuantity(CurrencyPair currencyPair);

	BigDecimal getOrderMinPrice(CurrencyPair currencyPair);

	BigDecimal getOrderMinTotal(CurrencyPair currencyPair);

	BigDecimal getOrderQuantityStepSize(CurrencyPair currencyPair);

	BigDecimal getOrderPriceStepSize(CurrencyPair currencyPair);

	CompletableFuture<OrderPair> cancelOrRevertLongOrder(CurrencyPair currencyPair, String longOrderId,
			OrderRollbackType rollbackType);

	CompletableFuture<OrderPair> cancelOrRevertShortOrder(CurrencyPair currencyPair, String shortOrderId,
			OrderRollbackType rollbackType);

	boolean isExceptionRetryable(Exception e);

	BigDecimal roundQuantityToStepSizeIfNecessary(boolean roundDown, BigDecimal quantity, CurrencyPair currencyPair);

	BigDecimal roundPriceToStepSizeIfNecessary(boolean roundDown, BigDecimal price, CurrencyPair currencyPair);

	QuoteGenerator createQuoteGenerator(QuoteService quoteService, EventListenerProvider eventListenerProvider);

}