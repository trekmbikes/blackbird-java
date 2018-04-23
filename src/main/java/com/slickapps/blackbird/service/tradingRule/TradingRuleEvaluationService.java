package com.slickapps.blackbird.service.tradingRule;

import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;

import java.math.BigDecimal;

import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.BigDecimalPair;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;

public class TradingRuleEvaluationService {
	private static final Logger log = LoggerFactory.getLogger(TradingRuleEvaluationService.class);

	public void evaluate(BigDecimalPair finalPrices, ExchangePairAndCurrencyPair ecp, BigDecimalPair quantities)
			throws TradingRuleViolationException {
		BigDecimal priceLong = finalPrices.getLong();
		BigDecimal priceShort = finalPrices.getShort();

		BigDecimal quantityLong = quantities.getLong();
		BigDecimal quantityShort = quantities.getShort();

		BigDecimal totalAmountLong = quantityLong.multiply(priceLong);
		BigDecimal totalAmountShort = quantityShort.multiply(priceShort);

		BlackbirdExchange longExchange = ecp.getLongExchange();
		BlackbirdExchange shortExchange = ecp.getShortExchange();
		CurrencyPair longCurrencyPair = ecp.getLongCurrencyPair();
		CurrencyPair shortCurrencyPair = ecp.getShortCurrencyPair();

		/* --------------------- Min Quantity --------------- */

		BigDecimal longMinQuantity = longExchange.getOrderMinQuantity(longCurrencyPair);
		if (longMinQuantity.compareTo(quantityLong) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current quantity required (%s) to satisfy transaction amount (%s)"
							+ " is less than the minimum supported by the exchange %s. Please increase"
							+ " exposure to at least %s to enable this market entry.",
					formatCurrency(longCurrencyPair.base, quantityLong), totalAmountLong, longExchange,
					formatCurrency(longCurrencyPair.counter, longMinQuantity.multiply(priceLong))));
		}

		BigDecimal shortMinQuantity = shortExchange.getOrderMinQuantity(shortCurrencyPair);
		if (shortMinQuantity.compareTo(quantityShort) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current quantity required (%s) to satisfy transaction amount (%s)"
							+ " is less than the minimum supported by the exchange %s. Please increase"
							+ " exposure to at least %s to enable this market entry.",
					formatCurrency(shortCurrencyPair.base, quantityShort), totalAmountShort, shortExchange,
					formatCurrency(shortCurrencyPair.counter, shortMinQuantity.multiply(priceShort))));
		}

		/* --------------------- Min Price --------------- */

		BigDecimal longMinPrice = longExchange.getOrderMinPrice(longCurrencyPair);
		if (longMinPrice != null && longMinPrice.compareTo(priceLong) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current price (%s)"
							+ " is less than the minimum supported by the exchange %s (%s).",
					formatCurrency(longCurrencyPair.counter, priceLong), longExchange,
					formatCurrency(longCurrencyPair.counter, longMinPrice)));
		}

		BigDecimal shortMinPrice = shortExchange.getOrderMinPrice(shortCurrencyPair);
		if (shortMinPrice != null && shortMinPrice.compareTo(priceShort) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current price (%s)"
							+ " is less than the minimum supported by the exchange %s (%s).",
					formatCurrency(shortCurrencyPair.counter, priceShort), shortExchange,
					formatCurrency(shortCurrencyPair.counter, shortMinPrice)));
		}

		/* --------------------- Min Total --------------- */

		BigDecimal longMinTotal = longExchange.getOrderMinTotal(longCurrencyPair);
		if (longMinTotal != null && longMinTotal.compareTo(totalAmountLong) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current total transaction amount (%s)"
							+ " is less than the minimum supported by the exchange %s (%s).",
					formatCurrency(longCurrencyPair.counter, totalAmountLong), longExchange,
					formatCurrency(longCurrencyPair.counter, longMinTotal)));
		}

		BigDecimal shortMinTotal = shortExchange.getOrderMinTotal(shortCurrencyPair);
		if (shortMinTotal != null && shortMinTotal.compareTo(totalAmountShort) > 0) {
			throw new TradingRuleViolationException(String.format(
					"Opportunity found but current total transaction amount (%s)"
							+ " is less than the minimum supported by the exchange %s (%s).",
					formatCurrency(shortCurrencyPair.counter, totalAmountShort), shortExchange,
					formatCurrency(shortCurrencyPair.counter, shortMinTotal)));
		}
	}

}
