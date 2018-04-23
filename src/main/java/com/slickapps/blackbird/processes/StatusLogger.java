package com.slickapps.blackbird.processes;

import static com.slickapps.blackbird.Main.stillRunning;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static com.slickapps.blackbird.util.FormatUtil.formatFriendlyDate;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.apache.commons.lang3.StringUtils.rightPad;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.DefaultBlackbirdEventListener;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;
import com.slickapps.blackbird.model.QuotePair;
import com.slickapps.blackbird.model.SpreadBounds;
import com.slickapps.blackbird.model.TrailingDetails;
import com.slickapps.blackbird.service.MarketEntryService;
import com.slickapps.blackbird.service.QuoteService;
import com.slickapps.blackbird.service.StringPaddingService;
import com.slickapps.blackbird.util.FormatUtil;

public class StatusLogger extends DefaultBlackbirdEventListener implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(StatusLogger.class);

	private Parameters params;
	private MarketPairsProvider marketPairsProvider;
	private MarketEntryService marketEntryService;
	private StringPaddingService stringPaddingService = new StringPaddingService();
	private QuoteService quoteService;
	private SpreadMonitor spreadMonitor;
	private boolean active = false;

	private SortedMap<ExchangePairAndCurrencyPair, QuotePair> latestEntryQuotes = new ConcurrentSkipListMap<>();

	public StatusLogger(MarketPairsProvider marketPairsProvider, MarketEntryService marketEntryService,
			QuoteService quoteService, SpreadMonitor spreadMonitor, Parameters params) {
		this.params = params;
		this.marketPairsProvider = marketPairsProvider;
		this.marketEntryService = marketEntryService;
		this.quoteService = quoteService;
		this.spreadMonitor = spreadMonitor;
	}

	public void activate() {
		active = true;
	}

	@Override
	public void quotePairEvaluation(QuotePair quotePair, boolean entryNotExit) {
		if (entryNotExit)
			latestEntryQuotes.put(new ExchangePairAndCurrencyPair(quotePair.getLongQuote(), quotePair.getShortQuote()),
					quotePair);
	}

	@Override
	public void entryOrdersPlaced(ExchangePairInMarket e) {
		/*
		 * This pair is entering the market so we can remove it from our entry quote map
		 */
		latestEntryQuotes.remove(e.toExchangePairAndCurrencyPair());
	}

	@Override
	public void run() {
		while (stillRunning) {
			try {
				Thread.sleep(params.infoLoggerPeriodMillis);
				if (!active)
					continue;

				LocalDateTime currTime = LocalDateTime.now();

				ExchangePairsInMarket exchangePairsInMarket = marketPairsProvider.getPairsInMarket();
				boolean hasPair = !latestEntryQuotes.isEmpty() || exchangePairsInMarket.getNumPairsInMarket() > 0;
				if (!hasPair)
					continue;

				log.info("");
				log.info("{}", formatFriendlyDate(currTime));
				log.info("[ pairs out of market ]");
				if (latestEntryQuotes.isEmpty()) {
					log.info("\t(none)");
				} else {
					printOutOfMarketInfo();
				}

				log.info("[ pairs in market ]");
				if (exchangePairsInMarket.getNumPairsInMarket() == 0) {
					log.info("\t(none)");
				} else {
					printInMarketInfo(exchangePairsInMarket);
				}
			} catch (InterruptedException e) {
				break;
			} catch (Exception e) {
				log.warn("Exception printing status:", e);
				continue;
			}
		}
	}

	public void printInMarketInfo(ExchangePairsInMarket exchangePairsInMarket) {
		NumberFormat pctF = FormatUtil.getPercentFormatter();

		exchangePairsInMarket.forEach(p -> {
			if (!p.getLongExchange().isEnabled() || !p.getShortExchange().isEnabled())
				return;

			Optional<Quote> longQuote = quoteService.getLatestQuote(p.toLongExchangeAndCurrencyPair());
			Optional<Quote> shortQuote = quoteService.getLatestQuote(p.toShortExchangeAndCurrencyPair());
			Optional<BigDecimal> targetSpread = Optional.ofNullable(p.getExitTarget());

			boolean hasBothQuotes = longQuote.isPresent() && shortQuote.isPresent();
			Optional<BigDecimal> currentSpread = hasBothQuotes
					? Optional.of(new QuotePair(longQuote.get(), shortQuote.get()).getSpreadIfExiting())
					: Optional.empty();

			String extra = "";
			if (!p.isBothEntryOrdersFilled()) {
				extra = " (awaiting order fulfillment)";
			} else if (hasBothQuotes) {
				BigDecimal totalProfit = p.getProposedProfitAfterFees(
						p.getEntryVolumeLong().multiply(longQuote.get().getBid()),
						p.getEntryVolumeShort().multiply(shortQuote.get().getAsk()));
				extra = " (" + formatCurrency(longQuote.get().getCurrencyPair().counter, totalProfit) + ")";

				TrailingDetails trailing = marketEntryService.getTrailingDetails(p.toExchangePairAndCurrencyPair());
				if (trailing.hasTrailingSpread()) {
					extra += String.format(" (trailing %s - %s/%s)", pctF.format(trailing.getTrailingStop()),
							trailing.getTrailingStopApprovalCount(), params.trailingRequiredConfirmationPeriods);
				}
			}
			printInfo(false, p.getLongExchange(), p.getLongCurrencyPair(), p.getShortExchange(),
					p.getShortCurrencyPair(), longQuote, shortQuote, currentSpread, targetSpread, extra);
		});
	}

	private void printOutOfMarketInfo() {
		NumberFormat pctF = FormatUtil.getPercentFormatter();

		for (Entry<ExchangePairAndCurrencyPair, QuotePair> entry : latestEntryQuotes.entrySet()) {
			ExchangePairAndCurrencyPair epcp = entry.getKey();
			if (!epcp.getLongExchange().isEnabled() || !epcp.getShortExchange().isEnabled())
				continue;

			QuotePair quotePair = entry.getValue();

			Quote longQuote = quotePair.getLongQuote();
			Quote shortQuote = quotePair.getShortQuote();
			BigDecimal currentSpread = new QuotePair(longQuote, shortQuote).getSpreadIfEntering();

			Optional<SpreadBounds> spreadBounds = spreadMonitor.getSpreadBounds(epcp);
			Optional<BigDecimal> targetSpread = Optional.empty();
			if (spreadBounds.isPresent())
				targetSpread = marketEntryService.getEntrySpreadUsingWindowAverage(epcp, spreadBounds.get());

			TrailingDetails trailing = marketEntryService.getTrailingDetails(epcp);
			String extra = null;
			if (trailing.hasTrailingSpread()) {
				extra = String.format(" (trailing %s - %s/%s)", pctF.format(trailing.getTrailingStop()),
						trailing.getTrailingStopApprovalCount(), params.trailingRequiredConfirmationPeriods);
			}

			printInfo(true, longQuote.getExchange(), longQuote.getCurrencyPair(), shortQuote.getExchange(),
					shortQuote.getCurrencyPair(), Optional.of(longQuote), Optional.of(shortQuote),
					Optional.of(currentSpread), targetSpread, extra);

			/*
			 * The short-term volatility is computed and displayed. No other action with it
			 * for the moment.
			 */
			// if (params.useVolatility) {
			// List<BigDecimal> volList = res.volatility.get(longName, shortName);
			// if (volList.size() >= params.volatilityPeriod) {
			// double stdev = compute_sd(volList);
			// log.info(" volat. {}", nf.format(stdev));
			// } else {
			// log.info(" volat. n/a {} < {}", volList.size(), params.volatilityPeriod);
			// }
			// }

		}
	}

	private void printInfo(boolean entryNotExit, BlackbirdExchange longExchange, CurrencyPair longCurrencyPair,
			BlackbirdExchange shortExchange, CurrencyPair shortCurrencyPair, Optional<Quote> longQuote,
			Optional<Quote> shortQuote, Optional<BigDecimal> spread, Optional<BigDecimal> targetSpread,
			String appendText) {
		NumberFormat pf = FormatUtil.getPercentFormatter();

		String quoteDetails = "";
		if (longQuote.isPresent() && shortQuote.isPresent()) {
			ExchangePairAndCurrencyPair ecp = new ExchangePairAndCurrencyPair(longQuote.get(), shortQuote.get());

			BigDecimal marketPriceLong = entryNotExit ? longQuote.get().getAsk() : longQuote.get().getBid();
			BigDecimal marketPriceShort = entryNotExit ? shortQuote.get().getBid() : shortQuote.get().getAsk();

			String longCur = formatCurrency(longCurrencyPair.counter, marketPriceLong);
			int longCurrencyLen = stringPaddingService.getMaxLen("longCurrencyStr", longCur);

			String shortCur = formatCurrency(shortCurrencyPair.counter, marketPriceShort);
			int shortCurrencyLen = stringPaddingService.getMaxLen("shortCurrencyStr", shortCur);
			String currenciesStr = leftPad(longCur, longCurrencyLen) + " vs " + leftPad(shortCur, shortCurrencyLen);

			Optional<SpreadBounds> spreadBounds = spreadMonitor.getSpreadBounds(ecp);

			String meanStr = "";
			if (spreadBounds.isPresent()) {
				SpreadBounds b = spreadBounds.get();
				if (b.hasWindowAverage()) {
					meanStr = pf.format(b.getWindowAverage());
				} else {
					long millisUntilWindowMet = b.getMillisUntilWindowMet();
					if (millisUntilWindowMet != -1)
						meanStr = format("ETA %s",
								DurationFormatUtils.formatDurationWords(millisUntilWindowMet, true, true));
				}
			}

			quoteDetails = format("%s tgt: %s [min %s, max %s, mean %s]", currenciesStr,
					leftPad(targetSpread.isPresent() ? pf.format(targetSpread.get()) : "pending", 7),
					leftPad(!spreadBounds.isPresent() || spreadBounds.get().getGlobalMin() == null ? "-"
							: pf.format(spreadBounds.get().getGlobalMin()), 6),
					leftPad(!spreadBounds.isPresent() || spreadBounds.get().getGlobalMax() == null ? "-"
							: pf.format(spreadBounds.get().getGlobalMax()), 6),
					leftPad(meanStr, 6));
		}

		String longStr = longCurrencyPair + " " + longExchange;
		longStr = rightPad(longStr, stringPaddingService.getMaxLen("longStr", longStr));

		String shortStr = shortCurrencyPair + " " + shortExchange;
		shortStr = rightPad(shortStr, stringPaddingService.getMaxLen("shortStr", shortStr));

		log.info("  L: {} S: {} @ {} {}{}", longStr, shortStr,
				leftPad(spread.isPresent() ? pf.format(spread.get()) : "", 6), quoteDetails,
				defaultIfBlank(appendText, ""));
	}

	public static StatusLogger initAndStart(Parameters params, MarketEntryService marketEntryService,
			QuoteService quoteService, SpreadMonitor spreadMonitor, MarketPairsProvider marketPairsProvider) {
		log.info("Starting status logger...");
		StatusLogger statusLogger = new StatusLogger(marketPairsProvider, marketEntryService, quoteService,
				spreadMonitor, params);
		Thread t = new Thread(statusLogger, "StatusLogger");
		t.setDaemon(true);
		t.start();
		return statusLogger;
	}

}
