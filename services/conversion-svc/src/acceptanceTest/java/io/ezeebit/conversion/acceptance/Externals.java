package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import javax.sql.DataSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// Started once per test JVM: the rate feed, the real ledger, and a proxy in front of the ledger to inject faults.
final class Externals {

	private static Externals instance;

	private final WireMockServer rates = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1")
			.usingFilesUnderDirectory(System.getProperty("rates.stubs")).globalTemplating(true));
	private final WireMockServer ledgerProxy = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
	private final LedgerProcess ledger = new LedgerProcess();
	private JdbcClient ledgerDb;

	private Externals() {
	}

	static synchronized Externals get() {
		if (instance == null) {
			Externals started = new Externals();
			try {
				started.start();
			}
			catch (Exception ex) {
				throw new IllegalStateException("could not start the acceptance externals", ex);
			}
			instance = started;
		}
		return instance;
	}

	private void start() throws Exception {
		rates.start();
		ledgerProxy.start();
		ledger.start();
		DataSource dataSource = new DriverManagerDataSource(ledger.database().getJdbcUrl(),
				ledger.database().getUsername(), ledger.database().getPassword());
		ledgerDb = JdbcClient.create(dataSource);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			rates.stop();
			ledgerProxy.stop();
		}));
	}

	WireMockServer rates() {
		return rates;
	}

	WireMockServer ledgerProxy() {
		return ledgerProxy;
	}

	String ledgerUrl() {
		return ledger.url();
	}

	JdbcClient ledgerDb() {
		return ledgerDb;
	}

}
