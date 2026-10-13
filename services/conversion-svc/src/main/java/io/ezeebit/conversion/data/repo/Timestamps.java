package io.ezeebit.conversion.data.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// The JDBC driver speaks OffsetDateTime for timestamptz, not Instant.
final class Timestamps {

	private Timestamps() {
	}

	static OffsetDateTime of(final Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	static Instant read(final ResultSet rs, final String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
