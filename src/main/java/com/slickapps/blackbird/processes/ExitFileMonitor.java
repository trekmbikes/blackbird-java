package com.slickapps.blackbird.processes;

import static com.slickapps.blackbird.Main.stillRunning;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExitFileMonitor implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(ExitFileMonitor.class);

	private static final File EXIT_FILE = new File("stop_after_notrade");
	
	private File file;

	public ExitFileMonitor(File file) {
		this.file = file;
	}

	@Override
	public void run() {
		while (stillRunning) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				break;
			}

			if (file.exists()) {
				log.info("Exiting after last trade (file {} found)", file);
				stillRunning = false;
			}
		}
	}

	public static ExitFileMonitor initAndStart() {
		log.info("Starting exit file monitor...");
		ExitFileMonitor exitFileMonitor = new ExitFileMonitor(EXIT_FILE);
		Thread t = new Thread(exitFileMonitor, "ExitFileMonitor");
		t.setDaemon(true);
		t.start();
		return exitFileMonitor;
	}

}
