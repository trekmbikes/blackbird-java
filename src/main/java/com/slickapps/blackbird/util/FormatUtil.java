package com.slickapps.blackbird.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.knowm.xchange.currency.Currency;

public class FormatUtil {

	public static final DateTimeFormatter USER_READABLE_DATE_TIME = DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss");

	public static NumberFormat getQuantityFormatter() {
		NumberFormat btcQuantityFormatter = NumberFormat.getNumberInstance();
		btcQuantityFormatter.setMinimumFractionDigits(6);
		btcQuantityFormatter.setMaximumFractionDigits(6);
		return btcQuantityFormatter;
	}

	public static NumberFormat getPercentFormatter() {
		NumberFormat nf2 = NumberFormat.getPercentInstance();
		nf2.setMinimumFractionDigits(2);
		nf2.setMaximumFractionDigits(2);
		return nf2;
	}

	public static String formatCurrency(Currency c, BigDecimal o) {
		if (c.equals(Currency.USD)) {
			DecimalFormat nf = (DecimalFormat) NumberFormat.getCurrencyInstance(Locale.US);
			String symbol = nf.getCurrency().getSymbol();
			nf.setNegativePrefix("-" + symbol);
			nf.setNegativeSuffix("");
			return nf.format(o);
		} else if (c.equals(Currency.USDT)) {
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMinimumFractionDigits(2);
			nf.setMaximumFractionDigits(2);
			return nf.format(o) + " " + c;
		} else {
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMinimumFractionDigits(6);
			nf.setMaximumFractionDigits(6);
			return nf.format(o) + " " + c;
		}
	}

	public static String formatFriendlyDate(LocalDateTime entryTime) {
		return entryTime.format(USER_READABLE_DATE_TIME);
	}

	// public static void main(String[] args) {
	// System.out.println(formatCurrency(Currency.USD, new BigDecimal("-2.3")));
	// System.out.println(formatCurrency(Currency.USDT, new BigDecimal("-2.3")));
	// }

}
