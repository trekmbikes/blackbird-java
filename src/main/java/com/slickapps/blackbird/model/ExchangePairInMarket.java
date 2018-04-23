package com.slickapps.blackbird.model;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.PUBLIC_ONLY;
import static com.slickapps.blackbird.Main.NL;
import static com.slickapps.blackbird.Main.TWO;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;
import static com.slickapps.blackbird.util.FormatUtil.formatFriendlyDate;
import static java.math.MathContext.DECIMAL64;
import static java.time.ZoneOffset.UTC;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.util.FormatUtil;

/**
 * Represents transaction information for a long/short position combination. Two
 * exchanges are involved, one where we open a short position (borrow and
 * immediately resell our short currency for some amount of the long currency)
 * and another where we open a long position (purchase some of the short
 * currency with existing long currency we own in our wallet). This also has
 * fields to represent the values after we close each position, which is useful
 * when exporting data.
 */
@JsonAutoDetect(fieldVisibility = NONE, getterVisibility = PUBLIC_ONLY, isGetterVisibility = PUBLIC_ONLY)
@ThreadSafe
public class ExchangePairInMarket implements Comparable<ExchangePairInMarket> {
	private static final Logger log = LoggerFactory.getLogger(ExchangePairInMarket.class);

	// ------------------------------------- Fields

	/* What exchange is participating in the long position */
	private BlackbirdExchange longExchange;
	/*
	 * When storing our state in an external file, just save the name and use it to
	 * restore the full Exchange on restart
	 */
	private String longExchangeName;

	private CurrencyPair longCurrencyPair;
	private String longBaseCurrencyCode;
	private String longCounterCurrencyCode;

	/* What exchange is participating in the short position */
	private BlackbirdExchange shortExchange;
	/*
	 * When storing our state in an external file, just save the name and use it to
	 * restore the full Exchange on restart
	 */
	private String shortExchangeName;

	private CurrencyPair shortCurrencyPair;
	private String shortBaseCurrencyCode;
	private String shortCounterCurrencyCode;

	/* See https://github.com/butor/blackbird/issues/12 */
	private BigDecimal trailing;
	private int trailingWaitCount;

	private int id;
	private BigDecimal exposure;
	private BigDecimal feePercentageLong;
	private BigDecimal feePercentageShort;

	private LocalDateTime entryTime;
	private String entryLongOrderId;
	private boolean entryLongOrderFilled;
	private String entryShortOrderId;
	private boolean entryShortOrderFilled;
	private BigDecimal entryVolumeLong;
	private BigDecimal entryPriceLong;
	private BigDecimal entryVolumeShort;
	private BigDecimal entryPriceShort;

	private BigDecimal exitTarget;

	private LocalDateTime exitTime;
	private String exitLongOrderId;
	private boolean exitLongOrderFilled;
	private String exitShortOrderId;
	private boolean exitShortOrderFilled;
	private BigDecimal exitVolumeLong;
	private BigDecimal exitPriceLong;
	private BigDecimal exitVolumeShort;
	private BigDecimal exitPriceShort;

	private AtomicLong versionCounter = new AtomicLong(0);

	// ------------------------------------- Constructor

	public ExchangePairInMarket() {
		/*
		 * to ensure we write a new but unmodified instance that's been added to the
		 * parent
		 */
		flagDirty();
	}

	public ExchangePairInMarket(ExchangePairAndCurrencyPair ecp) {
		setLongExchangeAndName(ecp.getLongExchange());
		setShortExchangeAndName(ecp.getShortExchange());
		setLongCurrencyPairAndCodes(ecp.getLongCurrencyPair());
		setShortCurrencyPairAndCodes(ecp.getShortCurrencyPair());
		flagDirty();
	}

	public ExchangePairInMarket(ExchangePairInMarket copy) {
		this.longExchange = copy.longExchange;
		this.longExchangeName = copy.longExchangeName;
		this.longCurrencyPair = copy.longCurrencyPair;
		this.longBaseCurrencyCode = copy.longBaseCurrencyCode;
		this.longCounterCurrencyCode = copy.longCounterCurrencyCode;
		this.shortExchange = copy.shortExchange;
		this.shortExchangeName = copy.shortExchangeName;
		this.shortCurrencyPair = copy.shortCurrencyPair;
		this.shortBaseCurrencyCode = copy.shortBaseCurrencyCode;
		this.shortCounterCurrencyCode = copy.shortCounterCurrencyCode;
		this.trailing = copy.trailing;
		this.trailingWaitCount = copy.trailingWaitCount;
		this.id = copy.id;
		this.exposure = copy.exposure;
		this.feePercentageLong = copy.feePercentageLong;
		this.feePercentageShort = copy.feePercentageShort;
		this.entryTime = copy.entryTime;
		this.entryLongOrderId = copy.entryLongOrderId;
		this.entryLongOrderFilled = copy.entryLongOrderFilled;
		this.entryShortOrderId = copy.entryShortOrderId;
		this.entryShortOrderFilled = copy.entryShortOrderFilled;
		this.entryVolumeLong = copy.entryVolumeLong;
		this.entryVolumeShort = copy.entryVolumeShort;
		this.entryPriceLong = copy.entryPriceLong;
		this.entryPriceShort = copy.entryPriceShort;
		this.exitTarget = copy.exitTarget;

		this.exitTime = copy.exitTime;
		this.exitLongOrderId = copy.exitLongOrderId;
		this.exitLongOrderFilled = copy.exitLongOrderFilled;
		this.exitShortOrderId = copy.exitShortOrderId;
		this.exitShortOrderFilled = copy.exitShortOrderFilled;
		this.exitVolumeLong = copy.exitVolumeLong;
		this.exitVolumeShort = copy.exitVolumeShort;
		this.exitPriceLong = copy.exitPriceLong;
		this.exitPriceShort = copy.exitPriceShort;

		this.versionCounter = new AtomicLong(copy.versionCounter.get());
	}

	// ------------------------------------- Business Methods

	private void flagDirty() {
		versionCounter.incrementAndGet();
	}

	@JsonIgnore
	public long getVersion() {
		return versionCounter.get();
	}

	public void resetVersion() {
		versionCounter.set(0);
	}

	@JsonIgnore
	public ExchangePairAndCurrencyPair toExchangePairAndCurrencyPair() {
		return new ExchangePairAndCurrencyPair(longExchange, longCurrencyPair, shortExchange, shortCurrencyPair);
	}

	@JsonIgnore
	public ExchangeAndCurrencyPair toLongExchangeAndCurrencyPair() {
		return new ExchangeAndCurrencyPair(longExchange, longCurrencyPair);
	}

	@JsonIgnore
	public ExchangeAndCurrencyPair toShortExchangeAndCurrencyPair() {
		return new ExchangeAndCurrencyPair(shortExchange, shortCurrencyPair);
	}

	@JsonIgnore
	public boolean isBothEntryOrdersPlaced() {
		return isEntryLongOrderPlaced() && isEntryShortOrderPlaced();
	}

	@JsonIgnore
	public boolean isBothEntryOrdersFilled() {
		return isEntryShortOrderFilled() && isEntryLongOrderFilled();
	}

	@JsonIgnore
	public boolean isEntryLongOrderPlaced() {
		return getEntryLongOrderId() != null;
	}

	@JsonIgnore
	public boolean isEntryShortOrderPlaced() {
		return getEntryShortOrderId() != null;
	}

	@JsonIgnore
	public boolean isExitLongOrderPlaced() {
		return getExitLongOrderId() != null;
	}

	@JsonIgnore
	public boolean isExitShortOrderPlaced() {
		return getExitShortOrderId() != null;
	}

	@JsonIgnore
	public boolean isBothExitOrdersPlaced() {
		return isExitLongOrderPlaced() && isExitShortOrderPlaced();
	}

	@JsonIgnore
	public boolean isEitherExitOrderPlaced() {
		return isExitLongOrderPlaced() || isExitShortOrderPlaced();
	}

	@JsonIgnore
	public boolean isBothExitOrdersFilled() {
		return isExitShortOrderFilled() && isExitLongOrderFilled();
	}

	public Optional<BigDecimal> targetPerfLong() {
		if (exitPriceLong == null || entryPriceLong == null || feePercentageLong == null)
			return Optional.empty();
		return Optional.of(exitPriceLong.subtract(entryPriceLong).divide(entryPriceLong, DECIMAL64)
				.subtract(feePercentageLong.multiply(TWO)));
	}

	public Optional<BigDecimal> targetPerfShort() {
		if (entryPriceShort == null || exitPriceShort == null || feePercentageShort == null)
			return Optional.empty();
		return Optional.of((entryPriceShort.subtract(exitPriceShort)).divide(entryPriceShort, DECIMAL64)
				.subtract(feePercentageShort.multiply(TWO)));
	}

	@JsonIgnore
	private BigDecimal getShortEntryTotalBeforeFees() {
		return getEntryVolumeShort().multiply(getEntryPriceShort());
	}

	@JsonIgnore
	private BigDecimal getLongEntryTotalBeforeFees() {
		return getEntryVolumeLong().multiply(getEntryPriceLong());
	}

	@JsonIgnore
	private BigDecimal getShortExitTotalBeforeFees() {
		return getExitVolumeShort().multiply(getExitPriceShort());
	}

	@JsonIgnore
	private BigDecimal getLongExitTotalBeforeFees() {
		return getExitVolumeLong().multiply(getExitPriceLong());
	}

	@JsonIgnore
	private BigDecimal getProposedTotalFees(BigDecimal exitLongTotalBeforeFees, BigDecimal exitShortTotalBeforeFees) {
		BigDecimal shortEntryFee = getShortEntryTotalBeforeFees().multiply(getFeePercentageShort());
		BigDecimal longEntryFee = getLongEntryTotalBeforeFees().multiply(getFeePercentageLong());
		BigDecimal shortExitFee = exitShortTotalBeforeFees.multiply(getFeePercentageShort());
		BigDecimal longExitFee = exitLongTotalBeforeFees.multiply(getFeePercentageLong());
		return shortEntryFee.add(longEntryFee).add(shortExitFee).add(longExitFee);
	}

	@JsonIgnore
	private BigDecimal getProposedProfit(BigDecimal exitLongTotalBeforeFees, BigDecimal exitShortTotalBeforeFees) {
		return BigDecimal.ZERO //
				.add(getShortEntryTotalBeforeFees()) //
				.subtract(getLongEntryTotalBeforeFees()) //
				.add(exitLongTotalBeforeFees) //
				.subtract(exitShortTotalBeforeFees);
	}

	@JsonIgnore
	public BigDecimal getProposedProfitAfterFees(BigDecimal exitLongTotalBeforeFees,
			BigDecimal exitShortTotalBeforeFees) {
		return getProposedProfit(exitLongTotalBeforeFees, exitShortTotalBeforeFees)
				.subtract(getProposedTotalFees(exitLongTotalBeforeFees, exitShortTotalBeforeFees));
	}

	@JsonIgnore
	private BigDecimal getFinalTotalFees() {
		return getProposedTotalFees(getLongExitTotalBeforeFees(), getShortExitTotalBeforeFees());
	}

	@JsonIgnore
	private BigDecimal getFinalProfitBeforeFees() {
		return getProposedProfit(getLongExitTotalBeforeFees(), getShortExitTotalBeforeFees());
	}

	@JsonIgnore
	public BigDecimal getFinalProfitAfterFees() {
		return getFinalProfitBeforeFees().subtract(getFinalTotalFees());
	}

	@JsonIgnore
	public double getTradeLengthInMinute() {
		if (entryTime != null && exitTime != null)
			return (exitTime.toEpochSecond(UTC) - entryTime.toEpochSecond(UTC)) / 60.0;
		return 0;
	}

	@JsonIgnore
	public String getTradeLengthDescription() {
		if (entryTime != null && exitTime != null)
			return DurationFormatUtils.formatDurationWords(ChronoUnit.MILLIS.between(entryTime, exitTime), true, true);
		return "Trades Incomplete";
	}

	@JsonIgnore
	public String getEntryInfo() {
		NumberFormat pct2 = FormatUtil.getPercentFormatter();

		StringBuilder sb = new StringBuilder();
		sb.append("[ ENTRY FOUND ]").append(NL);
		sb.append("   Date & Time:       ").append(FormatUtil.formatFriendlyDate(entryTime)).append(NL);
		sb.append("   Exchange Long:     ").append(longExchangeName).append(NL);
		sb.append("      Order ID:       ").append(entryLongOrderId).append(NL);
		sb.append("      Volume:         ").append(formatCurrency(getLongCurrencyPair().base, entryVolumeLong))
				.append(NL);
		sb.append("      Price:          ").append(formatCurrency(getLongCurrencyPair().counter, entryPriceLong))
				.append(" (target)").append(NL);
		// TODO are fees always in USD or in the counter currency?
		sb.append("      Fee %           ").append(pct2.format(feePercentageLong)).append(NL);
		sb.append("   Exchange Short:    ").append(shortExchangeName).append(NL);
		sb.append("      Order ID:       ").append(entryShortOrderId).append(NL);
		sb.append("      Volume:         ").append(formatCurrency(getShortCurrencyPair().base, entryVolumeShort))
				.append(NL);
		sb.append("      Price:          ").append(formatCurrency(getShortCurrencyPair().counter, entryPriceShort))
				.append(" (target)").append(NL);
		sb.append("      Fee %:          ").append(pct2.format(feePercentageShort)).append(NL);
		sb.append("   Spread:            ").append(pct2.format(getEntrySpread())).append(NL);

		/*
		 * TODO not sure what currency to use here, I guess we assume our exposure is
		 * equivalent for both USD and USDT
		 */
		sb.append("   Cash used:         ").append(formatCurrency(getShortCurrencyPair().counter, exposure))
				.append(" on each exchange").append(NL);
		sb.append("   Exit Target:       ").append(pct2.format(exitTarget));
		return sb.toString();
	}

	@JsonIgnore
	public String getExitInfo() {
		NumberFormat pct2 = FormatUtil.getPercentFormatter();

		StringBuilder sb = new StringBuilder();
		sb.append("[ EXIT FOUND ]").append(NL);
		sb.append("   Date & Time:       ").append(formatFriendlyDate(exitTime)).append(NL);
		sb.append("   Duration:          ").append(getTradeLengthDescription()).append(NL);
		sb.append("   Price Long:        ").append(
				exitPriceLong == null ? "Unknown" : formatCurrency(getLongCurrencyPair().counter, exitPriceLong))
				.append(NL);
		sb.append("   Price Short:       ")
				.append(exitPriceShort == null ? "Unknown"
						: formatCurrency(getShortCurrencyPair().counter, exitPriceShort))
				.append(" (target)").append(NL);

		sb.append("   Spread:            ")
				.append(getExitSpread().isPresent() ? pct2.format(getExitSpread().get()) : "Unknown").append(NL);
		sb.append("   ---------------------------").append(NL).append(NL);
		sb.append("   Target Perf Long:  ").append(
				targetPerfLong().isPresent() ? pct2.format(targetPerfLong().get()) + " (fees incl.)" : "Unknown")
				.append(NL);
		sb.append("   Target Perf Short: ").append(
				targetPerfShort().isPresent() ? pct2.format(targetPerfShort().get()) + " (fees incl.)" : "Unknown")
				.append(NL);
		sb.append("   ---------------------------");
		return sb.toString();
	}

	public void setLongExchangeAndName(BlackbirdExchange longExchange) {
		setLongExchange(longExchange);
		setLongExchangeName(longExchange.getName());
		flagDirty();
	}

	public void setShortExchangeAndName(BlackbirdExchange shortExchange) {
		setShortExchange(shortExchange);
		setShortExchangeName(shortExchange.getName());
		flagDirty();
	}

	public void setLongCurrencyPairAndCodes(CurrencyPair longCurrencyPair) {
		setLongCurrencyPair(longCurrencyPair);
		setLongBaseCurrencyCode(longCurrencyPair.base.getCurrencyCode());
		setLongCounterCurrencyCode(longCurrencyPair.counter.getCurrencyCode());
		flagDirty();
	}

	public void setShortCurrencyPairAndCodes(CurrencyPair shortCurrencyPair) {
		setShortCurrencyPair(shortCurrencyPair);
		setShortBaseCurrencyCode(shortCurrencyPair.base.getCurrencyCode());
		setShortCounterCurrencyCode(shortCurrencyPair.counter.getCurrencyCode());
		flagDirty();
	}

	@JsonIgnore
	public BigDecimal getEntrySpread() {
		return getEntryPriceShort().subtract(getEntryPriceLong()).divide(getEntryPriceLong(), DECIMAL64);
	}

	@JsonIgnore
	public Optional<BigDecimal> getExitSpread() {
		if (getExitPriceShort() == null || getExitPriceLong() == null)
			return Optional.empty();
		return Optional.of(getExitPriceShort().subtract(getExitPriceLong()).divide(getExitPriceLong(), DECIMAL64));
	}

	// ------------------------------------- Common Methods

	@Override
	public int hashCode() {
		return new HashCodeBuilder().append(id).toHashCode();
	}

	@Override
	public int compareTo(ExchangePairInMarket o) {
		if (equals(o))
			return 0;
		int val = new CompareToBuilder() //
				.append(longBaseCurrencyCode, o.longBaseCurrencyCode) //
				.append(longCounterCurrencyCode, o.longCounterCurrencyCode) //
				.append(longExchangeName, o.longExchangeName) //
				.append(shortBaseCurrencyCode, o.shortBaseCurrencyCode) //
				.append(shortCounterCurrencyCode, o.shortCounterCurrencyCode) //
				.append(shortExchangeName, o.shortExchangeName) //
				.toComparison() > 0 ? 1 : -1;
		return val;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		ExchangePairInMarket other = (ExchangePairInMarket) obj;
		return new EqualsBuilder().append(id, other.id).isEquals();
	}

	@Override
	public String toString() {
		return "EPIM: Long " + longExchange + " - " + longCurrencyPair + "; Short " + shortExchange + " - "
				+ shortCurrencyPair;
	}

	// ------------------------------------- Accessor Methods

	@JsonIgnore
	public BlackbirdExchange getShortExchange() {
		return shortExchange;
	}

	private void setShortExchange(BlackbirdExchange shortExchange) {
		this.shortExchange = shortExchange;
	}

	public String getShortExchangeName() {
		return shortExchangeName;
	}

	private void setShortExchangeName(String shortExchangeName) {
		this.shortExchangeName = shortExchangeName;
	}

	@JsonIgnore
	public CurrencyPair getLongCurrencyPair() {
		return longCurrencyPair;
	}

	private void setLongCurrencyPair(CurrencyPair longCurrencyPair) {
		this.longCurrencyPair = longCurrencyPair;
	}

	public String getLongBaseCurrencyCode() {
		return longBaseCurrencyCode;
	}

	private void setLongBaseCurrencyCode(String longBaseCurrencyCode) {
		this.longBaseCurrencyCode = longBaseCurrencyCode;
	}

	public String getLongCounterCurrencyCode() {
		return longCounterCurrencyCode;
	}

	private void setLongCounterCurrencyCode(String longCounterCurrencyCode) {
		this.longCounterCurrencyCode = longCounterCurrencyCode;
	}

	@JsonIgnore
	public CurrencyPair getShortCurrencyPair() {
		return shortCurrencyPair;
	}

	private void setShortCurrencyPair(CurrencyPair shortCurrencyPair) {
		this.shortCurrencyPair = shortCurrencyPair;
	}

	public String getShortBaseCurrencyCode() {
		return shortBaseCurrencyCode;
	}

	private void setShortBaseCurrencyCode(String shortBaseCurrencyCode) {
		this.shortBaseCurrencyCode = shortBaseCurrencyCode;
	}

	public String getShortCounterCurrencyCode() {
		return shortCounterCurrencyCode;
	}

	private void setShortCounterCurrencyCode(String shortCounterCurrencyCode) {
		this.shortCounterCurrencyCode = shortCounterCurrencyCode;
	}

	@JsonIgnore
	public BlackbirdExchange getLongExchange() {
		return longExchange;
	}

	private void setLongExchange(BlackbirdExchange longExchange) {
		this.longExchange = longExchange;
	}

	public String getLongExchangeName() {
		return longExchangeName;
	}

	private void setLongExchangeName(String longExchangeName) {
		this.longExchangeName = longExchangeName;
	}

	public BigDecimal getTrailing() {
		return trailing;
	}

	public void setTrailing(BigDecimal trailing) {
		this.trailing = trailing;
		flagDirty();
	}

	public int getTrailingWaitCount() {
		return trailingWaitCount;
	}

	public void setTrailingWaitCount(int trailingWaitCount) {
		this.trailingWaitCount = trailingWaitCount;
		flagDirty();
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
		flagDirty();
	}

	public BigDecimal getExposure() {
		return exposure;
	}

	public void setExposure(BigDecimal exposure) {
		this.exposure = exposure;
		flagDirty();
	}

	public BigDecimal getFeePercentageLong() {
		return feePercentageLong;
	}

	public void setFeePercentageLong(BigDecimal feePercentageLong) {
		this.feePercentageLong = feePercentageLong;
		flagDirty();
	}

	public BigDecimal getFeePercentageShort() {
		return feePercentageShort;
	}

	public void setFeePercentageShort(BigDecimal feePercentageShort) {
		this.feePercentageShort = feePercentageShort;
		flagDirty();
	}

	public LocalDateTime getEntryTime() {
		return entryTime;
	}

	public void setEntryTime(LocalDateTime entryTime) {
		this.entryTime = entryTime;
		flagDirty();
	}

	public LocalDateTime getExitTime() {
		return exitTime;
	}

	public void setExitTime(LocalDateTime exitTime) {
		this.exitTime = exitTime;
		flagDirty();
	}

	public BigDecimal getEntryVolumeLong() {
		return entryVolumeLong;
	}

	public void setEntryVolumeLong(BigDecimal volumeLong) {
		this.entryVolumeLong = volumeLong;
		flagDirty();
	}

	public BigDecimal getEntryVolumeShort() {
		return entryVolumeShort;
	}

	public void setEntryVolumeShort(BigDecimal volumeShort) {
		this.entryVolumeShort = volumeShort;
		flagDirty();
	}

	public BigDecimal getEntryPriceLong() {
		return entryPriceLong;
	}

	public void setEntryPriceLong(BigDecimal priceLongIn) {
		this.entryPriceLong = priceLongIn;
		flagDirty();
	}

	public BigDecimal getEntryPriceShort() {
		return entryPriceShort;
	}

	public void setEntryPriceShort(BigDecimal priceShortIn) {
		this.entryPriceShort = priceShortIn;
		flagDirty();
	}

	public BigDecimal getExitPriceLong() {
		return exitPriceLong;
	}

	public void setExitPriceLong(BigDecimal priceLongOut) {
		this.exitPriceLong = priceLongOut;
		flagDirty();
	}

	public BigDecimal getExitPriceShort() {
		return exitPriceShort;
	}

	public void setExitPriceShort(BigDecimal priceShortOut) {
		this.exitPriceShort = priceShortOut;
		flagDirty();
	}

	public BigDecimal getExitTarget() {
		return exitTarget;
	}

	public void setExitTarget(BigDecimal exitTarget) {
		this.exitTarget = exitTarget;
		flagDirty();
	}

	public String getEntryLongOrderId() {
		return entryLongOrderId;
	}

	public void setEntryLongOrderId(String orderIdLong) {
		this.entryLongOrderId = orderIdLong;
		flagDirty();
	}

	public String getEntryShortOrderId() {
		return entryShortOrderId;
	}

	public void setEntryShortOrderId(String shortOrderId) {
		this.entryShortOrderId = shortOrderId;
		flagDirty();
	}

	public boolean isEntryLongOrderFilled() {
		return entryLongOrderFilled;
	}

	public void setEntryLongOrderFilled(boolean longOrderFilled) {
		this.entryLongOrderFilled = longOrderFilled;
		flagDirty();
	}

	public boolean isEntryShortOrderFilled() {
		return entryShortOrderFilled;
	}

	public void setEntryShortOrderFilled(boolean shortOrderFilled) {
		this.entryShortOrderFilled = shortOrderFilled;
		flagDirty();
	}

	public String getExitLongOrderId() {
		return exitLongOrderId;
	}

	public void setExitLongOrderId(String exitLongOrderId) {
		this.exitLongOrderId = exitLongOrderId;
		flagDirty();
	}

	public boolean isExitLongOrderFilled() {
		return exitLongOrderFilled;
	}

	public void setExitLongOrderFilled(boolean exitLongOrderFilled) {
		this.exitLongOrderFilled = exitLongOrderFilled;
		flagDirty();
	}

	public String getExitShortOrderId() {
		return exitShortOrderId;
	}

	public void setExitShortOrderId(String exitShortOrderId) {
		this.exitShortOrderId = exitShortOrderId;
		flagDirty();
	}

	public boolean isExitShortOrderFilled() {
		return exitShortOrderFilled;
	}

	public void setExitShortOrderFilled(boolean exitShortOrderFilled) {
		this.exitShortOrderFilled = exitShortOrderFilled;
		flagDirty();
	}

	public BigDecimal getExitVolumeLong() {
		return exitVolumeLong;
	}

	public void setExitVolumeLong(BigDecimal exitVolumeLong) {
		this.exitVolumeLong = exitVolumeLong;
		flagDirty();
	}

	public BigDecimal getExitVolumeShort() {
		return exitVolumeShort;
	}

	public void setExitVolumeShort(BigDecimal exitVolumeShort) {
		this.exitVolumeShort = exitVolumeShort;
		flagDirty();
	}

}
