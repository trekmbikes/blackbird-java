package com.slickapps.blackbird.service;

import static java.math.BigDecimal.ZERO;
import static java.util.Collections.EMPTY_LIST;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.trade.UserTrade;

import com.slickapps.blackbird.util.MathUtil;

public class ExchangeCalculationService {

	public static class UserTradesAggregateResult {
		public Date earliestDate;
		public BigDecimal averagePrice;
		public BigDecimal sumTradeQuantities;
		public BigDecimal sumFees;
		public Currency feeCurrency;
		public OrderType orderType;
		public boolean foundMatchingTrades;

		public UserTradesAggregateResult(Date earliestDate, BigDecimal averagePrice, BigDecimal sumTradeQuantities,
				BigDecimal sumFees, Currency feeCurrency, OrderType orderType, boolean foundMatchingTrades) {
			this.earliestDate = earliestDate;
			this.averagePrice = averagePrice;
			this.sumTradeQuantities = sumTradeQuantities;
			this.sumFees = sumFees;
			this.feeCurrency = feeCurrency;
			this.orderType = orderType;
			this.foundMatchingTrades = foundMatchingTrades;
		}

		public BigDecimal getSumTradeQuantitiesAfterFees(CurrencyPair currencyPair) {
			return feeCurrency.equals(currencyPair.base) ? sumTradeQuantities.subtract(sumFees) : sumTradeQuantities;
		}
	}

	public UserTradesAggregateResult analyzeTrades(CurrencyPair currencyPair, String orderId,
			List<UserTrade> userTrades) {
		boolean foundMatchingTransactions = false;
		Date earliestDate = null;
		BigDecimal sumTradeQuantities = ZERO;
		BigDecimal sumFees = ZERO;
		Currency feeCurrency = null;
		OrderType orderType = null;
		List<BigDecimal[]> quantityAndPriceList = new ArrayList<>();

		for (UserTrade tr : userTrades) {
			if (!tr.getOrderId().equals(orderId))
				continue;

			foundMatchingTransactions = true;

			Date timestamp = tr.getTimestamp();
			if (timestamp != null && (earliestDate == null || timestamp.before(earliestDate)))
				earliestDate = timestamp;
			sumTradeQuantities = sumTradeQuantities.add(tr.getOriginalAmount());
			sumFees = sumFees.add(tr.getFeeAmount());
			/* Assume all user trades share the same fee currency */
			feeCurrency = tr.getFeeCurrency();
			/* Assume all user trades share the same order type */
			orderType = tr.getType();
			quantityAndPriceList.add(new BigDecimal[] { getTradeQuantityAfterFee(currencyPair, tr), tr.getPrice() });
		}

		return new UserTradesAggregateResult(earliestDate,
				foundMatchingTransactions ? MathUtil.calculateWeightedAverage(quantityAndPriceList) : null,
				sumTradeQuantities, sumFees, feeCurrency, orderType, foundMatchingTransactions);
	}

	public BigDecimal getTradeQuantityAfterFee(CurrencyPair currencyPair, UserTrade userTrade) {
		return currencyPair.base.equals(userTrade.getFeeCurrency())
				? userTrade.getOriginalAmount().subtract(userTrade.getFeeAmount())
				: userTrade.getOriginalAmount();
	}

	public List<CurrencyPair> parseCurrencyPairsCSV(String currencyPairsShortableStr) {
		@SuppressWarnings("unchecked")
		List<CurrencyPair> results = EMPTY_LIST;

		if (!isBlank(currencyPairsShortableStr)) {
			results = new ArrayList<>();
			for (String pairStr : currencyPairsShortableStr.split("\\s*,\\s*")) {
				String[] tokens = pairStr.split("\\s*/\\s*");
				results.add(new CurrencyPair(tokens[0].trim(), tokens[1].trim()));
			}
		}
		return results;
	}
}
