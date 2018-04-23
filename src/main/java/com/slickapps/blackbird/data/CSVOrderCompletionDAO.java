package com.slickapps.blackbird.data;

import static com.slickapps.blackbird.Main.TWO;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.DefaultBlackbirdEventListener;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;

public class CSVOrderCompletionDAO extends DefaultBlackbirdEventListener {
	private static final Logger log = LoggerFactory.getLogger(CSVOrderCompletionDAO.class);

	private static final String FILENAME_DATE_PATTERN = "yyyyMMdd_HHmmss";
	private static final String FILE_DATA_DATE_PATTERN = "yyyy-MM-dd_HH:mm:ss";
	private static final DateTimeFormatter CSV_FORMATTER = DateTimeFormatter.ofPattern(FILE_DATA_DATE_PATTERN);

	private BufferedWriter csvWriter;
	int numItemsWrote = 0;
	File csvFile;
	String formatLine;

	static final String[] COLS = new String[] { //
			"EXCHANGE_LONG", //
			"LONG_CURRENCY", //
			"EXCHANGE_SHORT", //
			"SHORT_CURRENCY", //
			"ENTRY_TIME", //
			"EXIT_TIME", //
			"DURATION_MINUTES", //
			"TOTAL_EXPOSURE", //
			"PROFIT", //
			"ENTRY_VOL_LONG", //
			"ENTRY_PRICE_LONG", //
			"ENTRY_VOL_SHORT", //
			"ENTRY_PRICE_SHORT", //
			"EXIT_VOL_LONG", //
			"EXIT_PRICE_LONG", //
			"EXIT_VOL_SHORT", //
			"EXIT_PRICE_SHORT", //
	};

	@Override
	public void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params)
			throws Exception {
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern(FILENAME_DATE_PATTERN);

		File outputDir = new File("output");
		Collection<File> existingResults = FileUtils.listFiles(outputDir, new WildcardFileFilter("blackbird_result_*"),
				null);
		for (File existingResult : existingResults) {
			if (existingResult.length() == 0)
				try {
					existingResult.delete();
				} catch (Exception e) {
					log.error("Couldn't delete empty file " + existingResult + "; skipping");
				}
		}

		// Creates the CSV file that will collect the trade results
		String csvFileName = "output/blackbird_result_" + dtf.format(LocalDateTime.now()) + ".csv";
		csvFile = new File(csvFileName);
		FileUtils.forceMkdirParent(csvFile);
		// Creates the log file where all events will be saved
		csvWriter = new BufferedWriter(new FileWriter(csvFile));
		csvWriter.write(StringUtils.join(COLS, ","));
		csvWriter.newLine();

		formatLine = StringUtils.repeat("%s", ",", COLS.length);
	}

	@Override
	public void orderComplete(ExchangePairInMarket p) {
		if (!p.isBothExitOrdersFilled())
			return;

		NumberFormat nf2 = NumberFormat.getNumberInstance();
		nf2.setMinimumFractionDigits(2);
		nf2.setMaximumFractionDigits(2);

		try {
			numItemsWrote++;
			String line = String.format(formatLine, //
					p.getLongExchangeName(), //
					p.getLongCurrencyPair(), //
					p.getShortExchangeName(), //
					p.getShortCurrencyPair(), //
					CSV_FORMATTER.format(p.getEntryTime()), //
					CSV_FORMATTER.format(p.getExitTime()), //
					p.getTradeLengthInMinute(), //
					nf2.format(p.getExposure().multiply(TWO)), //
					nf2.format(p.getFinalProfitAfterFees()), //
					p.getEntryVolumeLong(), //
					p.getEntryPriceLong(), //
					p.getEntryVolumeShort(), //
					p.getEntryPriceShort(), //
					p.getExitVolumeLong(), //
					p.getExitPriceLong(), //
					p.getExitVolumeShort(), //
					p.getExitPriceShort() //
			);
			csvWriter.write(line);
			csvWriter.newLine();
			csvWriter.flush();
		} catch (IOException e) {
			log.error("Couldn't write CSV output", e);
		}
	}

	@Override
	public void programExit() {
		try {
			csvWriter.close();
		} catch (IOException e) {
			log.error("Couldn't close the CSV writer", e);
		}

		if (numItemsWrote == 0) {
			if (csvFile != null) {
				boolean deleted = csvFile.delete();
				if (!deleted)
					log.error("Couldn't delete empty output CSV file {}", csvFile.getAbsolutePath());
			}
		}
	}

}
