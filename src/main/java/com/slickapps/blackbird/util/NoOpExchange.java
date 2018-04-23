package com.slickapps.blackbird.util;

import java.io.IOException;
import java.util.List;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.meta.ExchangeMetaData;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;

import si.mazi.rescu.SynchronizedValueFactory;

public class NoOpExchange implements Exchange {

	@Override
	public ExchangeSpecification getExchangeSpecification() {
		return null;
	}

	@Override
	public ExchangeMetaData getExchangeMetaData() {
		return null;
	}

	@Override
	public List<CurrencyPair> getExchangeSymbols() {
		return null;
	}

	@Override
	public SynchronizedValueFactory<Long> getNonceFactory() {
		return null;
	}

	@Override
	public ExchangeSpecification getDefaultExchangeSpecification() {
		return null;
	}

	@Override
	public void applySpecification(ExchangeSpecification exchangeSpecification) {
	}

	@Override
	public MarketDataService getMarketDataService() {
		return null;
	}

	@Override
	public TradeService getTradeService() {
		return null;
	}

	@Override
	public AccountService getAccountService() {
		return null;
	}

	@Override
	public void remoteInit() throws IOException, ExchangeException {
	}

}
