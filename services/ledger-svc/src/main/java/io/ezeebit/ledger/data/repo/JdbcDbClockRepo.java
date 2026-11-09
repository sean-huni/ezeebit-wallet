package io.ezeebit.ledger.data.repo;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// The ledger's own clock. clock_timestamp(), not now(): now() is frozen at the start of the transaction.
@Repository
class JdbcDbClockRepo implements DbClockRepo {

	private final JdbcClient jdbc;

	JdbcDbClockRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Instant now() {
		return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
	}

}
