package com.slickapps.blackbird.exchanges;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.gemini.v1.GeminiExchange;

import com.slickapps.blackbird.model.Parameters;

public class Gemini extends AbstractBlackbirdExchange {

	public Gemini(Parameters params) {
		initialize(params);
	}

	public Exchange createExchangeInstance() {
		return new GeminiExchange() {
		};
	}

}
