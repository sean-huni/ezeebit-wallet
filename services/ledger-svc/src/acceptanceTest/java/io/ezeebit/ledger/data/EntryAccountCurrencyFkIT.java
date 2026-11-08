package io.ezeebit.ledger.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import io.ezeebit.ledger.TestcontainersConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

// fk_entry_account_currency: (entry.account_id, entry.currency) -> (account.id, account.currency).
// Each claim is proven by the row it refuses, beside a control that differs only in the claim.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("acceptance")
@Transactional
class EntryAccountCurrencyFkIT {

	private static final String FK = "fk_entry_account_currency";

	@Autowired
	private JdbcClient jdbc;

	private UUID journal;

	private UUID account;

	@BeforeEach
	void zarMerchantAccountAndJournal() {
		journal = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO journal (id, merchant_id, type, status, idempotency_key, request_hash, reference)
				VALUES (:id, :merchant, 'DEPOSIT', 'POSTED', :key, 'h', 'r')""")
				.param("id", journal).param("merchant", UUID.randomUUID()).param("key", UUID.randomUUID().toString())
				.update();
		account = insertMerchantAccount("ZAR");
	}

	@Test
	void entryInTheAccountsOwnCurrencyIsAccepted() {
		assertThat(insertEntry(account, "ZAR")).isEqualTo(1);
	}

	@Test
	void entryInAnotherCurrencyThanItsAccountIsRefused() {
		assertThatThrownBy(() -> insertEntry(account, "USDT"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FK);
	}

	@Test
	void entryOnAnAccountThatDoesNotExistIsRefused() {
		assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "ZAR"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FK);
	}

	@Test
	void entryWithoutACurrencyCannotSkipTheCheck() {
		assertThatThrownBy(() -> insertEntry(account, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("null value in column \"currency\"");
	}

	@Test
	void accountWithoutEntriesMayChangeCurrency() {
		assertThat(changeCurrency(account, "NGN")).isEqualTo(1);
	}

	@Test
	void accountWithEntriesCannotChangeCurrency() {
		insertEntry(account, "ZAR");

		assertThatThrownBy(() -> changeCurrency(account, "NGN"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FK);
	}

	@Test
	void accountWithoutEntriesMayBeDeleted() {
		assertThat(delete(account)).isEqualTo(1);
	}

	@Test
	void accountWithEntriesCannotBeDeleted() {
		insertEntry(account, "ZAR");

		assertThatThrownBy(() -> delete(account))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(FK);
	}

	private UUID insertMerchantAccount(final String currency) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO account (id, merchant_id, currency, type, balance)
				VALUES (:id, :merchant, :currency, 'MERCHANT', 0)""")
				.param("id", id).param("merchant", UUID.randomUUID()).param("currency", currency).update();
		return id;
	}

	private int insertEntry(final UUID accountId, @Nullable final String currency) {
		return jdbc.sql("""
				INSERT INTO entry (id, journal_id, account_id, currency, amount)
				VALUES (:id, :journal, :account, :currency, 1)""")
				.param("id", UUID.randomUUID()).param("journal", journal).param("account", accountId)
				.param("currency", currency).update();
	}

	private int changeCurrency(final UUID accountId, final String currency) {
		return jdbc.sql("UPDATE account SET currency = :currency WHERE id = :id")
				.param("currency", currency).param("id", accountId).update();
	}

	private int delete(final UUID accountId) {
		return jdbc.sql("DELETE FROM account WHERE id = :id").param("id", accountId).update();
	}

}
