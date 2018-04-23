package com.slickapps.blackbird;

import static java.time.temporal.ChronoUnit.SECONDS;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.ClassUtils;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.slickapps.blackbird.data.CSVOrderCompletionDAO;
import com.slickapps.blackbird.data.DBQuoteWriter;
import com.slickapps.blackbird.data.EmailOrderCompletionDAO;
import com.slickapps.blackbird.data.ParametersDAO;
import com.slickapps.blackbird.data.SaveFileDAO;
import com.slickapps.blackbird.exchanges.AbstractBlackbirdExchange;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.BlackbirdEventListener;
import com.slickapps.blackbird.listener.SpreadMonitor;
import com.slickapps.blackbird.listener.VolatilityMonitor;
import com.slickapps.blackbird.model.ExchangeAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairAndCurrencyPair;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.ExchangePairsInMarket;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.processes.AutoFileSave;
import com.slickapps.blackbird.processes.ExchangeWalletPoller;
import com.slickapps.blackbird.processes.ExitFileMonitor;
import com.slickapps.blackbird.processes.StatusLogger;
import com.slickapps.blackbird.service.BalanceService;
import com.slickapps.blackbird.service.MarketEntryService;
import com.slickapps.blackbird.service.MarketExitService;
import com.slickapps.blackbird.service.QuoteService;
import com.slickapps.blackbird.util.FormatUtil;
import com.slickapps.blackbird.util.exception.ExceptionUtil;
import com.slickapps.blackbird.util.exception.ExchangeRuntimeException;
import com.slickapps.blackbird.util.exception.PairsInMarketUpdatedNotification;

public class Main implements MarketPairsProvider, EventListenerProvider {
	private static final Logger log = LoggerFactory.getLogger(Main.class);

	public static final String NL = System.lineSeparator();
	public static final File SAVE_FILE = new File("blackbird-save.json");

	public static final BigDecimal NEGATIVE_ONE = new BigDecimal(-1);
	public static final BigDecimal TWO = new BigDecimal(2);
	public static final BigDecimal _99_PERCENT = new BigDecimal(0.99);
	public static final BigDecimal _100 = new BigDecimal(100);

	/*
	 * Global flag to signify program execution should continue; false means we're
	 * exiting gracefully
	 */
	public static boolean stillRunning = true;
	private static final String PID_LOCK_FILENAME = "blackbird.lok";

	public static void main(String[] args) throws Exception {
		ensureSingleInstance();

		if (args.length != 1) {
			System.err.println("Please execute the program by providing the path to blackbird.conf");
			return;
		}

		if (!new File(args[0]).canRead()) {
			System.err.println("The file \"" + args[0] + "\" does not exist or could not be opened for reading.");
			return;
		}

		log.info("Blackbird Bitcoin Arbitrage (Java port)");
		log.info("DISCLAIMER: USE THE SOFTWARE AT YOUR OWN RISK");

		try {
			Parameters params = ParametersDAO.loadAndValidateParameters(args[0]);
			Main main = new Main(params);
			Runtime.getRuntime().addShutdownHook(main.getShutdownThread());
			main.runBlackbird();
		} catch (InterruptedException e) {
			// ignore, let's quit gracefully
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void ensureSingleInstance() throws IOException {
		String tempDir = System.getProperty("java.io.tmpdir");
		File pidLock = new File(tempDir, PID_LOCK_FILENAME);
		if (!pidLock.createNewFile()) {
			System.err.println("Another instance of Blackbird is currently executing. To force reset, remove the file "
					+ pidLock.getCanonicalPath());
			System.err.print("Ignore and proceed with execution? y/n > ");
			try (Scanner s = new Scanner(System.in);) {
				String l = s.nextLine();
				if ("y".equalsIgnoreCase(l)) {
					;
				} else {
					System.exit(1);
				}
			}
		}
		pidLock.deleteOnExit();
	}

	// --------------------------- Local state fields

	/*
	 * Fields package-private for less overhead in MarketPlacementService & other
	 * sister services
	 */

	Parameters params;

	// DAO instances
	DBQuoteWriter dBQuoteWriter;
	CSVOrderCompletionDAO csvOrderCompletionDAO;

	List<BlackbirdExchange> exchanges = new ArrayList<>();
	List<Thread> exchangeBackgroundJobs = new ArrayList<>();
	List<ExchangeAndCurrencyPair> exchangeAndCurrencyPairs;
	int maxCombinedNameLength;

	ExchangePairsInMarket exchangePairsInMarket;

	/* A utility to print information periodically */
	StatusLogger statusLogger;

	SpreadMonitor spreadMonitor;
	VolatilityMonitor volatilityMonitor;
	List<BlackbirdEventListener> eventListeners = new ArrayList<>();

	MarketEntryService marketEntryService;
	MarketExitService marketExitService;
	BalanceService balanceService;
	QuoteService quoteService;

	// --------------------------- App methods

	public Main(Parameters params) throws Exception {
		this.params = params;
		/* Other collections fields are populated in initialization methods below */
	}

	void runBlackbird() throws Exception {
		LocalDateTime startTime = LocalDateTime.now();
		RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
		long pid = Long.valueOf(runtimeBean.getName().split("@")[0]);
		System.out.println("Blackbird is starting... (pid " + pid + ")");
		logIntro();
		initResources();
		log.info("Blackbird started.");
		statusLogger.activate();

		while (stillRunning) {
			try {
				// let's not peg the CPU
				Thread.sleep(500);

				LocalDateTime currTime = LocalDateTime.now();
				if (params.debugMaxRuntimeSeconds != null
						&& SECONDS.between(startTime, currTime) > params.debugMaxRuntimeSeconds)
					// exit gracefully
					stillRunning = false;

				if (quoteService.hasNewQuote()) {
					processNewQuotes();
				}
			} catch (ExchangeRuntimeException e) {
				Throwable cause = e.getCause();
				if (ExceptionUtil.isRetryable(e.getExchange(), cause)) {
					log.warn("Encountered a communication exception with {}", e.getExchange());
					ExceptionUtil.disableExchange(e.getExchange());
				} else {
					log.warn("Encountered an exception", cause);
				}
			}
		}

		waitForTradeCompletion();
	}

	void processNewQuotes() throws IOException, InterruptedException, ExecutionException {
		try {
			/*
			 * Build two sets of pairs for individual processing, those in and those out of
			 * the market
			 */
			Set<ExchangePairAndCurrencyPair> inMarketPairs = inMarketPairs();
			Set<ExchangePairAndCurrencyPair> outOfMarketPairs = outOfMarketPairs(inMarketPairs);

			/* check for exit opportunities for any exchange pairs in market */
			ExchangePairInMarket pairExiting = marketExitService.prepareNextPairReadyToExit(exchangePairsInMarket);

			if (pairExiting != null) {
				marketExitService.beginExitOrderCompletionPollers(pairExiting);

				/*
				 * Break out of the search for ExchangePairInMarket's that need to exit (and
				 * restart the master loop immediately) just to be safe - this may be
				 * unnecessary but we'll pick up future exitable pairs immediately in the next
				 * loop
				 */
				throw new PairsInMarketUpdatedNotification();
			}

			/* Looks for new market opportunities on all the exchange combinations */
			ExchangePairInMarket pairEntering = marketEntryService.prepareNextPairReadyToEnter(outOfMarketPairs);

			if (pairEntering != null) {
				/*
				 * We found a new pair to add to the market; its entry orders have already been
				 * placed.
				 */
				exchangePairsInMarket.addPairInMarket(pairEntering);
				marketEntryService.beginEntryOrderCompletionPollers(pairEntering);
				log.info(pairEntering.getEntryInfo());

				/*
				 * Break out of the search for new arbitrage combinations (and restart the
				 * master loop immediately) since we just placed a new pair of market orders and
				 * the two exchange/currency combinations should no longer be available.
				 */
				throw new PairsInMarketUpdatedNotification();
			}

			/*
			 * reset the quote service so that we wait for a new quote to arrive before
			 * analyzing the loop again
			 */
			quoteService.allQuotesProcessed();
		} catch (PairsInMarketUpdatedNotification e) {
			quoteService.marketPairsUpdated();
		}
	}

	public Set<ExchangePairAndCurrencyPair> inMarketPairs() {
		return exchangePairsInMarket.getAsExchangePairAndCurrencyPairs();
	}

	@Override
	public ExchangePairsInMarket getPairsInMarket() {
		return exchangePairsInMarket;
	}

	@Override
	public SortedSet<ExchangePairAndCurrencyPair> getPairsOutOfMarket() {
		return outOfMarketPairs(inMarketPairs());
	}

	@Override
	public List<BlackbirdEventListener> getEventListeners() {
		return eventListeners;
	}

	protected SortedSet<ExchangePairAndCurrencyPair> outOfMarketPairs(Set<ExchangePairAndCurrencyPair> inMarketPairs) {
		SortedSet<ExchangePairAndCurrencyPair> results = new TreeSet<>();
		for (ExchangeAndCurrencyPair a : exchangeAndCurrencyPairs)
			for (ExchangeAndCurrencyPair b : exchangeAndCurrencyPairs) {
				ExchangePairAndCurrencyPair epcp = new ExchangePairAndCurrencyPair(a, b);
				if (!inMarketPairs.contains(epcp))
					results.add(epcp);
			}
		return results;
	}

	protected void initResources() throws Exception, IOException {
		initExchanges(null);

		exchangePairsInMarket = createOrImportPairsInMarket();

		/* Could do this dynamically / DI in the future - CPB */
		eventListeners.add(new EmailOrderCompletionDAO(params));
		eventListeners.add(csvOrderCompletionDAO = new CSVOrderCompletionDAO());
		eventListeners.add(dBQuoteWriter = new DBQuoteWriter());
		eventListeners.add(spreadMonitor = new SpreadMonitor(params));
		eventListeners.add(volatilityMonitor = new VolatilityMonitor());
		if (params.fileSaveEnabled)
			eventListeners.add(new AutoFileSave());

		/* Init services */
		quoteService = new QuoteService(params, this);
		marketEntryService = new MarketEntryService(params, this, this, quoteService, spreadMonitor);

		if (params.verbose) {
			statusLogger = StatusLogger.initAndStart(params, marketEntryService, quoteService, spreadMonitor, this);
			eventListeners.add(statusLogger);
		}

		for (BlackbirdEventListener l : eventListeners)
			l.init(exchanges, this, params);

		marketExitService = new MarketExitService(params, this, this, quoteService, spreadMonitor);

		balanceService = new BalanceService(params);
		balanceService.populateAndValidateBalances(exchanges, exchangePairsInMarket);
		ExchangeWalletPoller.initAndStart(params, exchanges, exchangePairsInMarket);

		/*
		 * If any of our pairs in market are trying to (enter or leave) and have their
		 * (entry/exit) orders only partially filled, let's begin monitoring for their
		 * completion
		 */
		for (ExchangePairInMarket unfilledOrder : exchangePairsInMarket.getPairsWithUnfilledEntryOrders())
			marketEntryService.beginEntryOrderCompletionPollers(unfilledOrder);
		for (ExchangePairInMarket unfilledOrder : exchangePairsInMarket.getPairsWithUnfilledExitOrders())
			marketExitService.beginExitOrderCompletionPollers(unfilledOrder);

		quoteService.initAndStartQuoteGenerators(exchanges);
		ExitFileMonitor.initAndStart();

		for (Thread t : exchangeBackgroundJobs) {
			log.info("Starting exchange background job {}.", t.getName());
			t.start();
		}
	}

	private void waitForTradeCompletion() {
		// TODO ?
	}

	/**
	 * Checks for (and imports data from) a saved data file, to see if the program
	 * exited with any open positions; if no file exists, initializes with empty
	 * ExchangePairsInMarket.
	 */
	protected ExchangePairsInMarket createOrImportPairsInMarket() throws IOException {
		ExchangePairsInMarket exchangePairsInMarket = new ExchangePairsInMarket();
		if (SAVE_FILE.canRead())
			exchangePairsInMarket = SaveFileDAO.fileImport(exchanges, SAVE_FILE);
		return exchangePairsInMarket;
	}

	private void logIntro() throws Exception {
		NumberFormat pct2 = FormatUtil.getPercentFormatter();

		// Log file header
		log.info("--------------------------------------------");
		log.info("|   Blackbird Bitcoin Arbitrage Log File   |");
		log.info("--------------------------------------------");
		log.info("Blackbird started on " + LocalDateTime.now());
		log.info("Connected to database \'" + params.dbFile + "\'");

		if (params.demoMode) {
			log.info("Demo mode: trades will be simulated based on real market values");
		}

		// Shows the spreads
		if (log.isInfoEnabled()) {
			log.info("   Profit Target:  {}", pct2.format(params.targetProfitPercentage));
		}

		log.info("");
		log.info("[ Max Exposures ]");
		for (Entry<Currency, BigDecimal> entry : params.getMaxExposureAmounts().entrySet()) {
			log.info("\t{}: {}", entry.getKey(), FormatUtil.formatCurrency(entry.getKey(), entry.getValue()));
		}
	}

	/* Package private to support unit testing */
	void initExchanges(List<BlackbirdExchange> exchangeOverrides) throws Exception {
		if (exchangeOverrides != null) {
			this.exchanges = exchangeOverrides;
		} else {
			ImmutableSet<ClassInfo> exchangeClasses = ClassPath.from(AbstractBlackbirdExchange.class.getClassLoader())
					.getTopLevelClasses(AbstractBlackbirdExchange.class.getPackage().getName());

			/*
			 * Initialize all exchange classes in the same package as
			 * AbstractArbitrageExchange that aren't abstract and implement the
			 * ArbitrageExchange interface
			 */
			exchangeClasses.forEach(p -> {
				Class<?> clazz = p.load();
				if (ClassUtils.getAllInterfaces(clazz).contains(BlackbirdExchange.class)
						&& !Modifier.isAbstract(clazz.getModifiers())) {
					try {
						Constructor<?> constr = clazz.getDeclaredConstructor(Parameters.class);
						BlackbirdExchange e = (BlackbirdExchange) constr.newInstance(params);
						exchanges.add(e);

						if (e.isEnabled()) {
							log.debug("Exchange initialization completed for {}.", e);

							for (Entry<String, Runnable> entry : e.getBackgroundJobs(this).entrySet()) {
								String threadName = entry.getKey();
								Runnable runnable = entry.getValue();
								Thread t = new Thread(runnable, threadName);
								t.setDaemon(true);
								exchangeBackgroundJobs.add(t);
							}
						}
					} catch (Exception e) {
						log.error("Couldn't initialize exchange " + clazz.getName()
								+ "; skipping it for this execution of Blackbird.");
						e.printStackTrace();
						// throw new RuntimeException(e);
					}
				}
			});
		}

		if (exchanges.size() < 2)
			throw new Exception(
					"Blackbird needs at least two exchanges. Please edit the configuration file to add new exchanges.");

		boolean isShortable = false;
		Set<CurrencyPair> longPositionCurrencyPairs = new HashSet<>();
		Set<CurrencyPair> shortPositionCurrencyPairs = new HashSet<>();

		for (BlackbirdExchange e : exchanges) {
			for (CurrencyPair cp : e.getCurrencyPairsForLongPositions())
				longPositionCurrencyPairs.add(cp);
			for (CurrencyPair cp : e.getCurrencyPairsForShortPositions())
				shortPositionCurrencyPairs.add(cp);
			isShortable |= e.isShortable();
		}

		if (!isShortable)
			throw new Exception("Blackbird needs at least one shortable exchange.");

		List<CurrencyPair> longPairsNotInShort = new ArrayList<>(longPositionCurrencyPairs);
		outer: //
		for (Iterator<CurrencyPair> longIt = longPairsNotInShort.iterator(); longIt.hasNext();) {
			CurrencyPair longCp = longIt.next();

			for (CurrencyPair shortCp : shortPositionCurrencyPairs) {
				if (params.currencyPairsEquivalent(longCp, shortCp)) {
					longIt.remove();
					continue outer;
				}
			}
		}

		if (!longPairsNotInShort.isEmpty())
			log.info("Removing long currency pairs {" + longPairsNotInShort
					+ "} since they aren't used by any short exchange.");

		List<CurrencyPair> shortPairsNotInLong = new ArrayList<>(shortPositionCurrencyPairs);
		outer: //
		for (Iterator<CurrencyPair> shortIt = shortPairsNotInLong.iterator(); shortIt.hasNext();) {
			CurrencyPair shortCp = shortIt.next();

			for (CurrencyPair longCp : longPositionCurrencyPairs) {
				if (params.currencyPairsEquivalent(longCp, shortCp)) {
					shortIt.remove();
					continue outer;
				}
			}
		}

		if (!shortPairsNotInLong.isEmpty())
			log.info("Removing short currency pairs {" + shortPairsNotInLong
					+ "} since they aren't used by any long exchange.");

		for (BlackbirdExchange e : exchanges) {
			e.getCurrencyPairsForLongPositions().removeAll(longPairsNotInShort);
			e.getCurrencyPairsForShortPositions().removeAll(shortPairsNotInLong);
		}

		Set<ExchangeAndCurrencyPair> uniqueCurrencyPairs = new HashSet<>();
		for (BlackbirdExchange e : exchanges) {
			for (CurrencyPair cp : e.getCurrencyPairsForLongPositions())
				uniqueCurrencyPairs.add(new ExchangeAndCurrencyPair(e, cp));
			for (CurrencyPair cp : e.getCurrencyPairsForShortPositions())
				uniqueCurrencyPairs.add(new ExchangeAndCurrencyPair(e, cp));
		}

		for (ExchangeAndCurrencyPair exchangeAndCurrencyPair : uniqueCurrencyPairs) {
			CurrencyPair currencyPair = exchangeAndCurrencyPair.getCurrencyPair();
			/* Both of these calls will throw an exception if the param isn't defined */
			params.getMaxTransactionAmount(currencyPair);
			params.getMaxExposureAmount(currencyPair.counter);
		}

		// safety first
		this.exchanges = Collections.unmodifiableList(exchanges);
		this.exchangeAndCurrencyPairs = Collections.unmodifiableList(new ArrayList<>(uniqueCurrencyPairs));
	}

	private Thread getShutdownThread() {
		return new Thread() {
			@Override
			public void run() {
				System.out.println("System exiting, please wait while cleaning up...");
				stillRunning = false;

				for (BlackbirdEventListener l : eventListeners)
					try {
						l.programExit();
					} catch (Throwable t) {
					}
			}
		};
	}

}
