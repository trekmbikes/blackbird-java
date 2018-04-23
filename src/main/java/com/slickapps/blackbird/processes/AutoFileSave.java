package com.slickapps.blackbird.processes;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.Main;
import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.data.SaveFileDAO;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.DefaultBlackbirdEventListener;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;

public class AutoFileSave extends DefaultBlackbirdEventListener {
	private static final Logger log = LoggerFactory.getLogger(AutoFileSave.class);

	private MarketPairsProvider marketPairsProvider;
	private long lastVersion = 0;
	private Thread thread;

	@Override
	public void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params)
			throws Exception {
		log.info("Starting AutoExport...");
		this.marketPairsProvider = marketPairsProvider;

		ExchangePairsInMarket exchangePairsInMarket = marketPairsProvider.getPairsInMarket();
		this.lastVersion = exchangePairsInMarket.getVersion();

		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (Main.stillRunning) {
					try {
						Thread.sleep(500);

						if (Main.stillRunning) {
							writeFileIfNeeded();
						}
					} catch (InterruptedException e) {
						return;
					} catch (IOException e) {
						log.error("Could not write to output file!", e);
						e.printStackTrace();
						return;
					}
				}
			}
		}, "FileAutoSaveMonitor");
		/*
		 * not a daemon so that it doesn't kill this midway thru execution if exiting
		 * the program
		 */
		thread.start();
	}

	@Override
	public void programExit() throws Exception {
		thread.interrupt();
		writeFileIfNeeded();
	}

	private synchronized void writeFileIfNeeded() throws IOException {
		ExchangePairsInMarket exchangePairsInMarket = marketPairsProvider.getPairsInMarket();

		long newVer = exchangePairsInMarket.getVersion();
		if (newVer > this.lastVersion) {
			/* Ensure thread safety during marshalization by using copy */
			ExchangePairsInMarket copy = exchangePairsInMarket.getPairsInMarketCopy(true);
			SaveFileDAO.fileExport(Main.SAVE_FILE, copy);
			this.lastVersion = newVer;
		}
	}

}