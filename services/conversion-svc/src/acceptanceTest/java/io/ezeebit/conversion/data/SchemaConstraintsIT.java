package io.ezeebit.conversion.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import io.ezeebit.conversion.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

// Each constraint is proven by its effect: the row it exists to refuse is written, and must fail.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "conversion.sweep-interval=1h")
@ActiveProfiles("acceptance")
@Transactional
class SchemaConstraintsIT {

	@Autowired
	private JdbcClient jdbc;

	private final UUID merchant = UUID.randomUUID();
	private UUID quote;

	@BeforeEach
	void quote() {
		quote = insertQuote("ZAR", "USDT");
	}

	@Test
	void aPendingConversionOfAQuoteIsAccepted() {
		assertThat(insertConversion(quote, "k-1", "PENDING", null, false)).isEqualTo(1);
	}

	@Test
	void aSecondConversionOfTheSameQuoteIsRefused() {
		insertConversion(quote, "k-1", "PENDING", null, false);

		assertThatThrownBy(() -> insertConversion(quote, "k-2", "PENDING", null, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_conversion_quote");
	}

	@Test
	void aSecondConversionWithTheSameMerchantAndKeyIsRefused() {
		insertConversion(quote, "k-1", "PENDING", null, false);

		assertThatThrownBy(() -> insertConversion(insertQuote("ZAR", "USDC"), "k-1", "PENDING", null, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_conversion_idempotency");
	}

	@Test
	void aCompletedConversionWithoutItsCompletionTimeIsRefused() {
		assertThatThrownBy(() -> insertConversion(quote, "k-1", "COMPLETED", null, false))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_conversion_shape");
	}

	@Test
	void aRejectedConversionWithoutAReasonIsRefused() {
		assertThatThrownBy(() -> insertConversion(quote, "k-1", "REJECTED", null, true))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_conversion_shape");
	}

	@Test
	void aStatusOutsideTheStateMachineIsRefused() {
		assertThatThrownBy(() -> insertConversion(quote, "k-1", "VOID", "VOIDED", true))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_conversion_shape");
	}

	@Test
	void aQuoteBetweenOneCurrencyAndItselfIsRefused() {
		assertThatThrownBy(() -> insertQuote("ZAR", "ZAR"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_quote_shape");
	}

	private UUID insertQuote(final String sell, final String buy) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO quote (id, merchant_id, sell_currency, buy_currency, sell_amount, buy_amount, rate, applied_rate,
				spread_bps, rate_source_timestamp, expires_at, status)
				VALUES (:id, :merchant, :sell, :buy, 100, 5.4, 0.054254, 0.05398273, 50, now(), now(), 'ACTIVE')""")
				.param("id", id).param("merchant", merchant).param("sell", sell).param("buy", buy).update();
		return id;
	}

	private int insertConversion(final UUID quoteId, final String key, final String status, final String reason,
			final boolean completed) {
		return jdbc.sql("""
				INSERT INTO conversion (id, merchant_id, quote_id, idempotency_key, status, reason, deadline, completed)
				VALUES (:id, :merchant, :quote, :key, :status, :reason, now(), CASE WHEN :completed THEN now() END)""")
				.param("id", UUID.randomUUID()).param("merchant", merchant).param("quote", quoteId).param("key", key)
				.param("status", status).param("reason", reason).param("completed", completed).update();
	}

}
