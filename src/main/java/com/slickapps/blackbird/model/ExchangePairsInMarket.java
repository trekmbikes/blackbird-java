package com.slickapps.blackbird.model;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.collections4.CollectionUtils;
import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;

@JsonAutoDetect(fieldVisibility = NONE, getterVisibility = ANY, isGetterVisibility = NONE)
@ThreadSafe
public class ExchangePairsInMarket {
	private static final Logger log = LoggerFactory.getLogger(ExchangePairsInMarket.class);

	/*
	 * This allows for 1 billion total combined changes for any single combination
	 * of the pairsInMarket. 9,223,372,036,754,775,807
	 */
	private static final long MAX_CHANGES_PER_CONFIG = 1000000000L;

	// --------------------------------------- Fields

	private SortedSet<ExchangePairInMarket> pairsInMarket;

	@JsonIgnore
	private AtomicLong pairsInMarketModifiedCounter = new AtomicLong(0);

	// --------------------------------------- Constructors

	public ExchangePairsInMarket() {
		pairsInMarket = Collections.synchronizedSortedSet(new TreeSet<>());
	}

	// --------------------------------------- Business Methods

	@JsonIgnore
	public long getVersion() {
		synchronized (pairsInMarket) {
			return (pairsInMarketModifiedCounter.get() * MAX_CHANGES_PER_CONFIG)
					+ pairsInMarket.stream().mapToLong(p -> p.getVersion()).sum();
		}
	}

	public void resetVersions() {
		pairsInMarketModifiedCounter.set(0);
		synchronized (pairsInMarket) {
			for (ExchangePairInMarket e : pairsInMarket)
				e.resetVersion();
		}
	}

	public void forEach(Consumer<? super ExchangePairInMarket> a) {
		synchronized (pairsInMarket) {
			pairsInMarket.forEach(a);
		}
	}

	public boolean isInMarket(BlackbirdExchange e, CurrencyPair currencyPair) {
		return getPairForExchangeAndCurrency(e, currencyPair) != null;
	}

	public ExchangePairsInMarket getPairsInMarketCopy(boolean deepCopy) {
		synchronized (pairsInMarket) {
			ExchangePairsInMarket copy = new ExchangePairsInMarket();
			SortedSet<ExchangePairInMarket> pairsInMarketCopy = new TreeSet<>();
			for (ExchangePairInMarket m : pairsInMarket) {
				pairsInMarketCopy.add(deepCopy ? new ExchangePairInMarket(m) : m);
			}
			copy.pairsInMarket = Collections.synchronizedSortedSet(pairsInMarketCopy);
			copy.pairsInMarketModifiedCounter.set(pairsInMarketModifiedCounter.get());
			return copy;
		}
	}

	@JsonIgnore
	public SortedSet<ExchangePairAndCurrencyPair> getAsExchangePairAndCurrencyPairs() {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().map(p -> p.toExchangePairAndCurrencyPair())
					.collect(Collectors.toCollection(TreeSet::new));
		}
	}

	public Set<ExchangePairInMarket> getPairsForExchange(BlackbirdExchange exchange, boolean includeLong,
			boolean includeShort) {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().filter(p -> (includeLong && exchange.equals(p.getLongExchange()))
					|| (includeShort && exchange.equals(p.getShortExchange()))).collect(Collectors.toSet());
		}
	}

	public ExchangePairInMarket getPairForExchangeAndCurrency(BlackbirdExchange e, CurrencyPair currencyPair) {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().filter(p -> p.getLongExchange().isEnabled()
					&& p.getShortExchange().isEnabled()
					&& ((p.getLongExchange().equals(e) && p.getLongCurrencyPair().equals(currencyPair))
							|| (p.getShortExchange().equals(e) && p.getShortCurrencyPair().equals(currencyPair))))
					.findFirst().orElse(null);
		}
	}

	public Set<ExchangePairInMarket> getPairsForCurrencyPair(CurrencyPair currencyPair) {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().filter(
					p -> p.getLongCurrencyPair().equals(currencyPair) || p.getShortCurrencyPair().equals(currencyPair))
					.collect(Collectors.toSet());
		}
	}

	@JsonIgnore
	public List<ExchangePairInMarket> getPairsWithUnfilledEntryOrders() {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().filter(p -> p.isBothEntryOrdersPlaced() && !p.isBothEntryOrdersFilled())
					.collect(Collectors.toList());
		}
	}

	@JsonIgnore
	public List<ExchangePairInMarket> getPairsWithUnfilledExitOrders() {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().filter(p -> p.isBothExitOrdersPlaced()
			/*
			 * if both orders are already filled on program start, we still want to activate
			 * the pollers so they are properly removed
			 */
			).collect(Collectors.toList());
		}
	}

	@JsonIgnore
	public int getMaxId() {
		synchronized (pairsInMarket) {
			return pairsInMarket.stream().mapToInt(p -> p.getId()).max().orElse(0);
		}
	}

	@JsonIgnore
	public BigDecimal getTotalExposure(CurrencyPair cp) {
		return getPairsForCurrencyPair(cp).stream().map(p -> p.getExposure()).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@JsonIgnore
	public int getNumPairsInMarket() {
		return pairsInMarket.size();
	}

	public void addPairInMarket(ExchangePairInMarket e) {
		pairsInMarketModifiedCounter.incrementAndGet();
		pairsInMarket.add(e);
	}

	public boolean removePairFromMarket(ExchangePairInMarket e) {
		pairsInMarketModifiedCounter.incrementAndGet();
		return pairsInMarket.remove(e);
	}

	public void filterExchanges(Collection<? extends BlackbirdExchange> exchanges) {
		Map<String, BlackbirdExchange> exchangesByName = new HashMap<>();
		for (BlackbirdExchange e : exchanges)
			exchangesByName.put(e.getName(), e);

		synchronized (pairsInMarket) {
			for (Iterator<ExchangePairInMarket> it = pairsInMarket.iterator(); it.hasNext();) {
				ExchangePairInMarket epim = it.next();

				BlackbirdExchange longExchange = exchangesByName.get(epim.getLongExchangeName());
				BlackbirdExchange shortExchange = exchangesByName.get(epim.getShortExchangeName());
				/*
				 * if our incoming file references an exchange that we didn't activate or don't
				 * support, ignore this incoming data element entirely
				 */
				if (longExchange == null || shortExchange == null) {
					if (longExchange == null)
						log.warn("Ignoring saved exchange " + epim.getLongExchangeName());
					if (shortExchange == null)
						log.warn("Ignoring saved exchange " + epim.getShortExchangeName());
					continue;
				}

				epim.setLongExchangeAndName(longExchange);
				epim.setLongCurrencyPairAndCodes(
						new CurrencyPair(epim.getLongBaseCurrencyCode(), epim.getLongCounterCurrencyCode()));

				epim.setShortExchangeAndName(shortExchange);
				epim.setShortCurrencyPairAndCodes(
						new CurrencyPair(epim.getShortBaseCurrencyCode(), epim.getShortCounterCurrencyCode()));
			}
		}
	}

	/**
	 * @return A list of the ExchangePairInMarket - synchronize on this list
	 *         externally during iteration.
	 */
	public SortedSet<ExchangePairInMarket> getPairsInMarket() {
		return pairsInMarket;
	}

	@SuppressWarnings("unused")
	private void setPairsInMarket(Collection<ExchangePairInMarket> incoming) {
		pairsInMarket.clear();
		if (CollectionUtils.isNotEmpty(incoming))
			pairsInMarket.addAll(incoming);
	}

}
