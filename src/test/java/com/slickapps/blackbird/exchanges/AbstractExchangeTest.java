package com.slickapps.blackbird.exchanges;

import java.math.BigDecimal;

import org.junit.Test;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.model.tradingRules.TradingRule;

public class AbstractExchangeTest {

	@Test
	public void testStepSize() {
		AbstractBlackbirdExchange e = new AbstractBlackbirdExchange() {
			@Override
			protected Exchange createExchangeInstance() {
				return null;
			}
		};
		TradingRule tr = new TradingRule();
		tr.setMinQuantity(new BigDecimal("10.331"));
		tr.setStepSizeForQuantity(new BigDecimal("0.001"));
		tr.setMinPrice(new BigDecimal("10.283"));
		tr.setStepSizeForPrice(new BigDecimal("0.002"));
		e.tradingRulesByCurrencyPair.put(CurrencyPair.BTC_USD, tr);

		BigDecimal vol = new BigDecimal(12.25867);
		BigDecimal result = e.roundQuantityToStepSizeIfNecessary(true, vol, CurrencyPair.BTC_USD);
		System.out.println(vol + " - " + result);
		result = e.roundQuantityToStepSizeIfNecessary(false, vol, CurrencyPair.BTC_USD);
		System.out.println(vol + " - " + result);

		result = e.roundPriceToStepSizeIfNecessary(true, vol, CurrencyPair.BTC_USD);
		System.out.println(vol + " - " + result);
		result = e.roundPriceToStepSizeIfNecessary(false, vol, CurrencyPair.BTC_USD);
		System.out.println(vol + " - " + result);

	}

}
