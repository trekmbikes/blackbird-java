package com.slickapps.blackbird.listener;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.map.MultiKeyMap;

import com.google.common.collect.EvictingQueue;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;

public class VolatilityMonitor extends DefaultBlackbirdEventListener {

	MultiKeyMap<String, EvictingQueue<BigDecimal>> volatility = new MultiKeyMap<>();

	@Override
	public void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params)
			throws Exception {
		/* Informational only at this point */
		for (BlackbirdExchange e1 : exchanges)
			for (BlackbirdExchange e2 : exchanges)
				volatility.put(e1.getName(), e2.getName(), EvictingQueue.create(params.volatilityPeriod));
	}

	private void populateVolatilities(Map<BlackbirdExchange, Quote> quoteMap, ExchangePairInMarket res) {
		// for (ArbitrageExchange i : exchanges) {
		// for (ArbitrageExchange j : exchanges) {
		// if (i == j)
		// continue;
		//
		// Quote bitcoinI = quoteMap.get(i);
		// Quote bitcoinJ = quoteMap.get(j);
		//
		// if (j.isShortable()) {
		// BigDecimal longMidPrice = bitcoinI.getMidPrice();
		// BigDecimal shortMidPrice = bitcoinJ.getMidPrice();
		// if (longMidPrice.signum() == 1 && shortMidPrice.signum() == 1) {
		// List<BigDecimal> list = res.volatility.get(i.getName(), j.getName());
		// if (list.size() >= params.volatilityPeriod) {
		// list.remove(list.size() - 1);
		// }
		// list.add(0,
		// ((longMidPrice.subtract(shortMidPrice)).divide(longMidPrice,
		// MathContext.DECIMAL64)));
		// }
		// }
		// }
		// }
	}
	//
	// private static double compute_sd(List<BigDecimal> l) {
	// return new
	// StandardDeviation().evaluate(l.stream().mapToDouble(BigDecimal::doubleValue).toArray());
	// }
}
