package io.ezeebit.conversion.data.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.QuoteEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteRepo {

	static final String COLUMNS = """
			q.id, q.merchant_id, q.sell_currency, q.buy_currency, q.sell_amount, q.buy_amount, q.rate, q.applied_rate,
			q.spread_bps, q.rate_source_timestamp, q.expires_at, q.status""";

	private final JdbcClient jdbc;

	public QuoteRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(final QuoteEntity quote) {
		jdbc.sql("""
				INSERT INTO quote (id, merchant_id, sell_currency, buy_currency, sell_amount, buy_amount, rate, applied_rate,
				spread_bps, rate_source_timestamp, expires_at, status)
				VALUES (:id, :merchantId, :sellCurrency, :buyCurrency, :sellAmount, :buyAmount, :rate, :appliedRate,
				:spreadBps, :rateSourceTimestamp, :expiresAt, :status)""")
				.param("id", quote.id()).param("merchantId", quote.merchantId())
				.param("sellCurrency", quote.sellCurrency()).param("buyCurrency", quote.buyCurrency())
				.param("sellAmount", quote.sellAmount()).param("buyAmount", quote.buyAmount())
				.param("rate", quote.rate()).param("appliedRate", quote.appliedRate()).param("spreadBps", quote.spreadBps())
				.param("rateSourceTimestamp", Timestamps.of(quote.rateSourceTimestamp()))
				.param("expiresAt", Timestamps.of(quote.expiresAt())).param("status", quote.status())
				.update();
	}

	// Another merchant's quote is simply not found: no hint that it exists.
	public Optional<QuoteEntity> findForMerchant(final UUID id, final UUID merchantId) {
		return jdbc.sql("SELECT %s FROM quote q WHERE q.id = :id AND q.merchant_id = :merchantId".formatted(COLUMNS))
				.param("id", id).param("merchantId", merchantId)
				.query((rs, row) -> read(rs)).optional();
	}

	// The market rate of the pair's most recent quote, if it is younger than `since`.
	public Optional<BigDecimal> recentRate(final String sell, final String buy, final Instant since) {
		return jdbc.sql("""
				SELECT rate FROM quote WHERE sell_currency = :sell AND buy_currency = :buy AND created > :since
				ORDER BY created DESC LIMIT 1""")
				.param("sell", sell).param("buy", buy).param("since", Timestamps.of(since))
				.query(BigDecimal.class).optional();
	}

	public void markUsed(final UUID id) {
		jdbc.sql("UPDATE quote SET status = 'USED' WHERE id = :id").param("id", id).update();
	}

	static QuoteEntity read(final ResultSet rs) throws SQLException {
		return new QuoteEntity(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
				rs.getString("sell_currency"), rs.getString("buy_currency"), rs.getBigDecimal("sell_amount"),
				rs.getBigDecimal("buy_amount"), rs.getBigDecimal("rate"), rs.getBigDecimal("applied_rate"),
				rs.getInt("spread_bps"), Timestamps.read(rs, "rate_source_timestamp"), Timestamps.read(rs, "expires_at"),
				rs.getString("status"));
	}

}
