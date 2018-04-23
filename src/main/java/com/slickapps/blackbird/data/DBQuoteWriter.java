package com.slickapps.blackbird.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.slickapps.blackbird.MarketPairsProvider;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.listener.DefaultBlackbirdEventListener;
import com.slickapps.blackbird.model.Parameters;
import com.slickapps.blackbird.model.Quote;

public class DBQuoteWriter extends DefaultBlackbirdEventListener {
	private static final Logger log = LoggerFactory.getLogger(DBQuoteWriter.class);

	private Connection connection;
	private Parameters params;

	@Override
	public void init(List<BlackbirdExchange> exchanges, MarketPairsProvider marketPairsProvider, Parameters params) throws Exception {
		Class.forName("org.sqlite.JDBC");
		connection = DriverManager.getConnection("jdbc:sqlite:" + params.dbFile);
		this.params = params;

		for (BlackbirdExchange exchange : exchanges)
			createTableIfNeeded(exchange.getDbTableName(), params);
	}

	private void createTableIfNeeded(String exchangeName, Parameters params) throws SQLException {
		String query = "CREATE TABLE IF NOT EXISTS `" + exchangeName
				+ "` (Datetime DATETIME NOT NULL, bid DECIMAL(8, 2), ask DECIMAL(8, 2));";
		try (Statement stmt = connection.createStatement();) {
			stmt.execute(query);
		} catch (SQLException e) {
			System.err.println(e.getErrorCode() + " - " + e.getMessage());
			throw e;
		}
	}

	private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss");

	@Override
	public void quoteReceived(Quote q) {
		String exchangeName = q.getExchange().getDbTableName();
		String datetime = dtf.format(q.getCreationTime());
		String bid = String.valueOf(q.getBid());
		String ask = String.valueOf(q.getAsk());

		String query = "INSERT INTO `" + exchangeName + "` VALUES ('" + datetime + "'," + bid + "," + ask + ");";
		try (Statement stmt = connection.createStatement();) {
			stmt.execute(query);
		} catch (SQLException e) {
			log.error("Couldn't write quote to DB", e);
		}
	}

}
