package io.ezeebit.ledger.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import io.ezeebit.ledger.TestcontainersConfiguration;
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
@SpringBootTest
@ActiveProfiles("acceptance")
@Transactional
class SchemaConstraintsIT {

	private static final UUID ZAR_CLEARING = UUID.fromString("00000000-0000-0000-0001-000000000001");

	@Autowired
	private JdbcClient jdbc;

	private UUID journal;

	@BeforeEach
	void journal() {
		journal = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO journal (id, merchant_id, type, status, idempotency_key, request_hash, reference)
				VALUES (:id, :merchant, 'DEPOSIT', 'POSTED', :key, 'h', 'r')""")
				.param("id", journal).param("merchant", UUID.randomUUID()).param("key", UUID.randomUUID().toString())
				.update();
	}

	@Test
	void entryInTheAccountsOwnCurrencyIsAccepted() {
		assertThat(insertEntry(ZAR_CLEARING, "ZAR")).isEqualTo(1);
	}

	@Test
	void entryInAnotherCurrencyThanItsAccountIsRefusedByTheDatabase() {
		assertThatThrownBy(() -> insertEntry(ZAR_CLEARING, "USDT"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_entry_account_currency");
	}

	@Test
	void merchantBalanceBelowZeroIsRefusedByTheDatabase() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO account (id, merchant_id, currency, type, balance)
				VALUES (:id, :merchant, 'ZAR', 'MERCHANT', -0.01)""")
				.param("id", UUID.randomUUID()).param("merchant", UUID.randomUUID()).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_account_shape");
	}

	@Test
	void secondPlatformClearingAccountForOneCurrencyIsRefused() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO account (id, merchant_id, currency, type, balance)
				VALUES (:id, NULL, 'ZAR', 'PLATFORM_DEPOSIT_CLEARING', NULL)""")
				.param("id", UUID.randomUUID()).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_account_platform_currency_type");
	}

	@Test
	void secondJournalWithTheSameMerchantTypeAndKeyIsRefused() {
		UUID merchant = UUID.randomUUID();
		insertJournal(merchant, "k-1");

		assertThatThrownBy(() -> insertJournal(merchant, "k-1"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_journal_idempotency");
	}

	@Test
	void zeroAmountEntryIsRefused() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO entry (id, journal_id, account_id, currency, amount)
				VALUES (:id, :journal, :account, 'ZAR', 0)""")
				.param("id", UUID.randomUUID()).param("journal", journal).param("account", ZAR_CLEARING).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_entry_amount_nonzero");
	}

	private int insertEntry(final UUID account, final String currency) {
		return jdbc.sql("""
				INSERT INTO entry (id, journal_id, account_id, currency, amount)
				VALUES (:id, :journal, :account, :currency, -1)""")
				.param("id", UUID.randomUUID()).param("journal", journal).param("account", account)
				.param("currency", currency).update();
	}

	private void insertJournal(final UUID merchant, final String key) {
		jdbc.sql("""
				INSERT INTO journal (id, merchant_id, type, status, idempotency_key, request_hash, reference)
				VALUES (:id, :merchant, 'DEPOSIT', 'POSTED', :key, 'h', 'r')""")
				.param("id", UUID.randomUUID()).param("merchant", merchant).param("key", key).update();
	}

}
