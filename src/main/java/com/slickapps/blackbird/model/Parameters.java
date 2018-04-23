package com.slickapps.blackbird.model;

import static com.slickapps.blackbird.Main._100;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static java.math.BigDecimal.ZERO;
import static java.math.MathContext.DECIMAL64;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;

public class Parameters {
	private static final Logger log = LoggerFactory.getLogger(Parameters.class);

	private Properties params = new Properties();
	public Integer debugMaxRuntimeSeconds;
	public int spreadAverageWindowLengthSeconds;
	public int spreadWindowValidAfterSeconds;
	public boolean adaptToWindowAverage;
	
	public BigDecimal targetProfitPercentage;
	public long maxQuoteTimeDifferenceMillis;
	public BigDecimal maxLimitPriceDifference;
	public BigDecimal trailingSpreadLim;
	public int trailingRequiredConfirmationPeriods;

	public BigDecimal orderBookFactor;
	public boolean demoMode;
	public boolean fileSaveEnabled;
	private Map<Currency, BigDecimal> maxInitialCurrenciesAllowed = new HashMap<>();
	private Map<CurrencyPair, BigDecimal> maxTransactionAmounts = new HashMap<>();
	private Map<Currency, BigDecimal> maxExposureAmounts = new HashMap<>();

	public boolean verbose;
	public long infoLoggerPeriodMillis;

	public boolean useVolatility;
	public int volatilityPeriod;
	public long orderCompletionMaxExecutionMillis;

	public boolean sendEmail;
	public String senderAddress;
	public String senderUsername;
	public String senderPassword;
	public String receiverAddress;

	public String dbFile;

	private Map<Currency, Set<Currency>> equivalentCurrencies;

	public void setFromProperties(Properties properties) throws IOException {
		this.params = properties;

		try {
			for (Field field : FieldUtils.getAllFields(Parameters.class)) {
				String propName = StringUtils.capitalize(field.getName());
				if (properties.containsKey(propName)) {
					String propVal = properties.getProperty(propName);
					if (propVal != null && StringUtils.isNotBlank(propVal)) {
						field.setAccessible(true);
						Class<?> fieldType = field.getType();

						if (fieldType == BigDecimal.class) {
							field.set(this, new BigDecimal(propVal));
						} else if (fieldType == Integer.class || fieldType == int.class) {
							field.set(this, Integer.valueOf(propVal));
						} else if (fieldType == Long.class || fieldType == long.class) {
							field.set(this, Long.valueOf(propVal));
						} else if (fieldType == Boolean.class || fieldType == boolean.class) {
							field.set(this, Boolean.valueOf(propVal));
						} else if (fieldType == String.class) {
							field.set(this, propVal);
						}
					}
				} else {
					int modifiers = field.getModifiers();
					if (isPublic(modifiers) && !isStatic(modifiers)) {
						log.warn("Parameter {} defined but not set in the configuration file", field.getName());
					}
				}
			}
		} catch (IllegalArgumentException | IllegalAccessException e) {
			throw new RuntimeException("Couldn't set property val", e);
		}

		if (targetProfitPercentage != null)
			targetProfitPercentage = targetProfitPercentage.divide(_100, DECIMAL64);

		equivalentCurrencies = parseEquivalentCurrenciesCSV(getString("EquivalentCurrencies", ""));

		// ------------------- dynamic behavior

		Iterators.forEnumeration(properties.propertyNames()).forEachRemaining(p -> {
			String propName = String.valueOf(p);
			if (propName.startsWith("MaxInitialCurrency")) {
				String currencyCode = propName.substring("MaxInitialCurrency".length());
				maxInitialCurrenciesAllowed.put(Currency.getInstance(currencyCode), getBigDecimal(propName, ZERO));
			} else if (propName.startsWith("MaxTransactionAmount")) {
				String currencyCode = propName.substring("MaxTransactionAmount".length());
				maxTransactionAmounts.put(new CurrencyPair(currencyCode), getBigDecimal(propName, ZERO));
			} else if (propName.startsWith("MaxExposure")) {
				String currencyCode = propName.substring("MaxExposure".length());
				maxExposureAmounts.put(Currency.getInstance(currencyCode), getBigDecimal(propName, ZERO));
			}
		});

		maxInitialCurrenciesAllowed = Collections.unmodifiableMap(maxInitialCurrenciesAllowed);
		maxExposureAmounts = Collections.unmodifiableMap(maxExposureAmounts);
	}

	private Map<Currency, Set<Currency>> parseEquivalentCurrenciesCSV(String equivalentCurrenciesStr) {
		Map<Currency, Set<Currency>> equivalentCurrencies = new HashMap<>();

		if (!StringUtils.isBlank(equivalentCurrenciesStr)) {
			for (String pairStr : equivalentCurrenciesStr.split("\\s*,\\s*")) {
				String[] tokens = pairStr.split("\\s*\\:\\s*");
				Currency c1 = new Currency(tokens[0].trim());
				Currency c2 = new Currency(tokens[1].trim());
				equivalentCurrencies.computeIfAbsent(c1, p -> new HashSet<>()).add(c2);
				equivalentCurrencies.computeIfAbsent(c2, p -> new HashSet<>()).add(c1);
			}
		}
		return equivalentCurrencies;
	}

	/**
	 * Gets all equivalent currencies to the specified Currency (not including the
	 * specified Currency)
	 */
	public Set<Currency> getEquivalentCurrencies(Currency c) {
		Set<Currency> set = equivalentCurrencies.get(c);
		return set == null ? new HashSet<>() : set;
	}

	public boolean currenciesEquivalent(Currency c1, Currency c2) {
		if (c1.equals(c2))
			return true;

		return getEquivalentCurrencies(c1).contains(c2);
	}

	public boolean currencyPairsEquivalent(CurrencyPair cp1, CurrencyPair cp2) {
		if (cp1.equals(cp2))
			return true;
		
		return currenciesEquivalent(cp1.base, cp2.base) && currenciesEquivalent(cp1.counter, cp2.counter);
	}

	public Map<Currency, BigDecimal> getMaxExposureAmounts() {
		return maxExposureAmounts;
	}

	public BigDecimal getMaxExposureAmount(Currency c) {
		BigDecimal max = maxExposureAmounts.get(c);
		if (max != null)
			return max;

		for (Currency equivalentCurrency : getEquivalentCurrencies(c)) {
			max = maxExposureAmounts.get(equivalentCurrency);
			if (max != null)
				return max;
		}
		throw new IllegalArgumentException("Missing maximum exposure allowed parameter for " + c);
	}

	public BigDecimal getMaxInitialCurrency(Currency c) {
		BigDecimal max = maxInitialCurrenciesAllowed.get(c);
		if (max != null)
			return max;

		for (Currency equivalentCurrency : getEquivalentCurrencies(c)) {
			max = maxInitialCurrenciesAllowed.get(equivalentCurrency);
			if (max != null)
				return max;
		}
		throw new IllegalArgumentException("Missing maximum currency allowed parameter for " + c);
	}

	public BigDecimal getMaxTransactionAmount(CurrencyPair c) {
		BigDecimal max = maxTransactionAmounts.get(c);
		if (max != null)
			return max;

		Set<Currency> equivalentBases = getEquivalentCurrencies(c.base);
		equivalentBases.add(c.base);

		Set<Currency> equivalentCounters = getEquivalentCurrencies(c.counter);
		equivalentCounters.add(c.counter);

		for (Currency equivalentBase : equivalentBases) {
			for (Currency equivalentCounter : equivalentCounters) {
				max = maxTransactionAmounts.get(new CurrencyPair(equivalentBase, equivalentCounter));
				if (max != null)
					return max;
			}
		}
		throw new IllegalArgumentException("Missing maximum currency allowed parameter for " + c);
	}

	public String getString(String key, String defaultIfMissing) {
		return StringUtils.isNotBlank(params.getProperty(key)) ? params.getProperty(key) : defaultIfMissing;
	}

	public BigDecimal getBigDecimal(String key, BigDecimal defaultIfMissing) {
		return params.containsKey(key) && StringUtils.isNotBlank(params.getProperty(key))
				? new BigDecimal(params.getProperty(key))
				: defaultIfMissing;
	}

	public Boolean getBoolean(String key, Boolean defaultIfMissing) {
		return params.containsKey(key) && StringUtils.isNotBlank(params.getProperty(key))
				? Boolean.valueOf(params.getProperty(key))
				: defaultIfMissing;
	}

	public Integer getInteger(String key, Integer defaultIfMissing) {
		return params.containsKey(key) && StringUtils.isNotBlank(params.getProperty(key))
				? new Integer(params.getProperty(key))
				: defaultIfMissing;
	}

	public Long getLong(String key, Long defaultIfMissing) {
		return params.containsKey(key) && StringUtils.isNotBlank(params.getProperty(key))
				? new Long(params.getProperty(key))
				: defaultIfMissing;
	}

	public boolean isSpecified(String prop) {
		return StringUtils.isNotBlank(params.getProperty(prop));
	}

	public Properties getAllParams() {
		return params;
	}

}
