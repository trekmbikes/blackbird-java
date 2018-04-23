package com.slickapps.blackbird;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.Test;
import org.knowm.xchange.currency.CurrencyPair;

import com.slickapps.blackbird.data.EmailOrderCompletionDAO;
import com.slickapps.blackbird.exchanges.MockExchange;
import com.slickapps.blackbird.model.ExchangePairInMarket;

public class TestEmail extends AbstractBlackbirdTest {

	@Test
	public void testEmail() throws Exception {
		EmailOrderCompletionDAO orderCompletionDAO = new EmailOrderCompletionDAO(createTestParameters());
		ExchangePairInMarket epim = new ExchangePairInMarket();

		epim.setLongExchangeAndName(new MockExchange("A", 0.2, 9, new double[][] {}).makeShortable());
		epim.setShortExchangeAndName(new MockExchange("B", 0.2, 9, new double[][] {}).makeShortable());
		epim.setLongCurrencyPairAndCodes(CurrencyPair.BTC_USDT);
		epim.setShortCurrencyPairAndCodes(CurrencyPair.BTC_USD);
		epim.setEntryLongOrderId("ENTRY_LONG_ORDER_ID");
		epim.setEntryLongOrderFilled(true);
		epim.setEntryPriceLong(new BigDecimal("9750"));
		epim.setEntryPriceShort(new BigDecimal("9500"));
		epim.setEntryVolumeLong(new BigDecimal("0.06"));
		epim.setEntryVolumeShort(new BigDecimal("0.05"));
		epim.setEntryShortOrderFilled(true);
		epim.setEntryShortOrderId("ENTRY_SHORT_ORDER_ID");
		epim.setEntryTime(LocalDateTime.now().minusHours(2));
		epim.setExposure(new BigDecimal("585"));
		epim.setExitLongOrderId("ENTRY_LONG_ORDER_ID");
		epim.setExitLongOrderFilled(true);
		epim.setExitPriceLong(new BigDecimal("9600"));
		epim.setExitPriceShort(new BigDecimal("9600"));
		epim.setExitVolumeLong(new BigDecimal("0.055"));
		epim.setExitVolumeShort(new BigDecimal("0.055"));
		epim.setExitShortOrderFilled(true);
		epim.setExitShortOrderId("EXIT_SHORT_ORDER_ID");
		epim.setExitTime(LocalDateTime.now().minusHours(1));
		epim.setFeePercentageLong(new BigDecimal("0.01"));
		epim.setFeePercentageShort(new BigDecimal("0.02"));
		orderCompletionDAO.sendEmail(epim);
	}

}
