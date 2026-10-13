package io.ezeebit.ledger.data.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.model.EntryEntity;
import io.ezeebit.ledger.data.model.StatementFilter;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EntryRepo {

	private static final String LINES = """
			SELECT e.seq, e.journal_id, j.type, j.reference, e.currency, e.amount, e.balance_after, e.created
			FROM entry e
			JOIN account a ON a.id = e.account_id AND a.type = 'MERCHANT'
			JOIN journal j ON j.id = e.journal_id
			""";

	private final JdbcClient jdbc;

	public EntryRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(final EntryEntity entry) {
		jdbc.sql("""
				INSERT INTO entry (id, journal_id, account_id, currency, amount, balance_after)
				VALUES (:id, :journalId, :accountId, :currency, :amount, :balanceAfter)""")
				.paramSource(entry)
				.update();
	}

	public StatementLineEntity merchantLine(final UUID journalId) {
		return jdbc.sql("%s WHERE e.journal_id = :journalId".formatted(LINES))
				.param("journalId", journalId)
				.query(EntryRepo::line)
				.single();
	}

	public List<StatementLineEntity> merchantLines(final UUID journalId) {
		return jdbc.sql("%s WHERE e.journal_id = :journalId ORDER BY e.seq".formatted(LINES))
				.param("journalId", journalId)
				.query(EntryRepo::line)
				.list();
	}

	public List<StatementLineEntity> statement(final StatementFilter filter) {
		StringBuilder sql = new StringBuilder(LINES).append("WHERE a.merchant_id = :merchantId AND e.seq > :after");
		Map<String, Object> params = new HashMap<>();
		params.put("merchantId", filter.merchantId());
		params.put("after", filter.after());
		params.put("limit", filter.limit());
		String currency = filter.currency();
		if (currency != null) {
			sql.append(" AND e.currency = :currency");
			params.put("currency", currency);
		}
		Instant from = filter.from();
		if (from != null) {
			sql.append(" AND e.created >= :from");
			params.put("from", from.atOffset(ZoneOffset.UTC));
		}
		Instant to = filter.to();
		if (to != null) {
			sql.append(" AND e.created < :to");
			params.put("to", to.atOffset(ZoneOffset.UTC));
		}
		sql.append(" ORDER BY e.seq LIMIT :limit");
		return jdbc.sql(sql.toString()).params(params).query(EntryRepo::line).list();
	}

	private static StatementLineEntity line(final ResultSet rs, final int row) throws SQLException {
		return new StatementLineEntity(rs.getLong("seq"), rs.getObject("journal_id", UUID.class), rs.getString("type"),
				rs.getString("reference"), rs.getString("currency"), rs.getBigDecimal("amount"),
				rs.getBigDecimal("balance_after"), rs.getObject("created", OffsetDateTime.class).toInstant());
	}

}
