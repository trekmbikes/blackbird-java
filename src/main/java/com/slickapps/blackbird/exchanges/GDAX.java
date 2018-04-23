package com.slickapps.blackbird.exchanges;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.gdax.GDAXExchange;

import com.slickapps.blackbird.model.Parameters;

public class GDAX extends AbstractBlackbirdExchange {

	public GDAX(Parameters params) {
		initialize(params);
	}

	public Exchange createExchangeInstance() {
		return new GDAXExchange() {
		};
	}

}
