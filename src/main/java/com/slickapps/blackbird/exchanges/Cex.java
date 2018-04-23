package com.slickapps.blackbird.exchanges;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.cexio.CexIOExchange;
import org.knowm.xchange.exceptions.ExchangeException;

import com.slickapps.blackbird.model.Parameters;

public class Cex extends AbstractBlackbirdExchange {

	public Cex(Parameters params) {
		initialize(params);
	}

	public Exchange createExchangeInstance() {
		return new CexIOExchange() {
		};
	}

	@Override
	protected ExchangeSpecification getExchangeSpec() throws ExchangeException {
		ExchangeSpecification e = super.getExchangeSpec();
		// copying example; this may not be necessary
		e.setSslUri("https://cex.io");
		return e;
	}
}
