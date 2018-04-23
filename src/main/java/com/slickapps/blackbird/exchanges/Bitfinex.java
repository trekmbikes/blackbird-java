package com.slickapps.blackbird.exchanges;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.bitfinex.v1.BitfinexExchange;

import com.slickapps.blackbird.model.Parameters;

public class Bitfinex extends AbstractBlackbirdExchange {

	public Bitfinex(Parameters params) {
		initialize(params);
	}

	protected Exchange createExchangeInstance() {
		return new BitfinexExchange();
	}

}
