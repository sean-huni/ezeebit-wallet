package io.ezeebit.ledger.data.repo;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// The ledger's own clock. clock_timestamp(), not now(): now() is frozen at the start of the transaction.
@Repository
public class DbClockRepo {

	private final JdbcClient jdbc;

	public DbClockRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Instant now() {
		return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
	}

}
