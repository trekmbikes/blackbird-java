package com.slickapps.blackbird.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

public class MathUtil {

	/**
	 * @param vals
	 *            An array of BigDecimal pairs, the first item in each pair being
	 *            the quantity and the second being the price.
	 * @return The weighted average of the specified vals
	 */
	public static BigDecimal calculateWeightedAverage(List<BigDecimal[]> vals) {
		if (CollectionUtils.isEmpty(vals))
			throw new IllegalArgumentException("vals was empty or null");

		BigDecimal totalNumerator = BigDecimal.ZERO;
		BigDecimal totalDenominator = BigDecimal.ZERO;
		for (BigDecimal[] pair : vals) {
			if (pair[0].signum() != 1)
				throw new IllegalArgumentException("A non-positive quantity was encountered: " + pair[0]);

			totalNumerator = totalNumerator.add(pair[0].multiply(pair[1]));
			totalDenominator = totalDenominator.add(pair[0]);
		}

		return totalNumerator.divide(totalDenominator, MathContext.DECIMAL64);
	}
	
	

}
