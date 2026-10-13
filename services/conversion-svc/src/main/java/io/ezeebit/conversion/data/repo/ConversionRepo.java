package io.ezeebit.conversion.data.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.ConversionEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversionRepo {

	private static final String SELECT = """
			SELECT c.id AS c_id, c.merchant_id AS c_merchant_id, c.quote_id, c.idempotency_key, c.status AS c_status,
			c.reason, c.deadline, c.created AS c_created, c.completed, %s
			FROM conversion c JOIN quote q ON q.id = c.quote_id""".formatted(QuoteRepo.COLUMNS);

	private final JdbcClient jdbc;

	public ConversionRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// UNIQUE (quote_id) and UNIQUE (merchant_id, idempotency_key) raise DuplicateKeyException here.
	public void insertPending(final UUID id, final UUID merchantId, final UUID quoteId, final String key,
			final Instant deadline) {
		jdbc.sql("""
				INSERT INTO conversion (id, merchant_id, quote_id, idempotency_key, status, deadline)
				VALUES (:id, :merchantId, :quoteId, :key, 'PENDING', :deadline)""")
				.param("id", id).param("merchantId", merchantId).param("quoteId", quoteId).param("key", key)
				.param("deadline", Timestamps.of(deadline))
				.update();
	}

	public Optional<ConversionEntity> findByKey(final UUID merchantId, final String key) {
		return jdbc.sql(SELECT + " WHERE c.merchant_id = :merchantId AND c.idempotency_key = :key")
				.param("merchantId", merchantId).param("key", key)
				.query((rs, row) -> read(rs)).optional();
	}

	public Optional<ConversionEntity> find(final UUID id, final UUID merchantId) {
		return jdbc.sql(SELECT + " WHERE c.id = :id AND c.merchant_id = :merchantId")
				.param("id", id).param("merchantId", merchantId)
				.query((rs, row) -> read(rs)).optional();
	}

	// Compare-and-set: only a PENDING row moves, so the accept path and the sweeper can never both settle it.
	public int settle(final UUID id, final String status, final String reason, final Instant completed) {
		return jdbc.sql("""
				UPDATE conversion SET status = :status, reason = :reason, completed = :completed
				WHERE id = :id AND status = 'PENDING'""")
				.param("id", id).param("status", status).param("reason", reason)
				.param("completed", Timestamps.of(completed))
				.update();
	}

	// One due row per call, locked; a row another replica holds is skipped, never waited on.
	public Optional<ConversionEntity> claimDue(final Instant cutoff, final List<UUID> skip) {
		String excluded = skip.isEmpty() ? "" : " AND c.id NOT IN (:skip)";
		var spec = jdbc.sql(SELECT + " WHERE c.status = 'PENDING' AND c.deadline < :cutoff" + excluded
				+ " ORDER BY c.deadline LIMIT 1 FOR UPDATE OF c SKIP LOCKED")
				.param("cutoff", Timestamps.of(cutoff));
		if (!skip.isEmpty()) {
			spec = spec.param("skip", skip);
		}
		return spec.query((rs, row) -> read(rs)).optional();
	}

	private static ConversionEntity read(final ResultSet rs) throws SQLException {
		return new ConversionEntity(rs.getObject("c_id", UUID.class), rs.getObject("c_merchant_id", UUID.class),
				rs.getObject("quote_id", UUID.class), rs.getString("idempotency_key"), rs.getString("c_status"),
				rs.getString("reason"), Timestamps.read(rs, "deadline"), Timestamps.read(rs, "c_created"),
				Timestamps.read(rs, "completed"), QuoteRepo.read(rs));
	}

}
