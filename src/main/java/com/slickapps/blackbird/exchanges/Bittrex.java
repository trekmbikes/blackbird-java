package com.slickapps.blackbird.exchanges;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.bittrex.BittrexExchange;

import com.slickapps.blackbird.model.Parameters;

public class Bittrex extends AbstractBlackbirdExchange {

	public Bittrex(Parameters params) {
		initialize(params);
	}

	@Override
	protected Exchange createExchangeInstance() {
		return new BittrexExchange() {
		};
	}

}
