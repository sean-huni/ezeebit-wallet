package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import org.springframework.jdbc.core.simple.JdbcClient;

// Reads the ledger's own tables: the reconciliation a client cannot see through the API.
public class LedgerIntegritySteps {

	private final ScenarioState state;
	private final JdbcClient jdbc;

	public LedgerIntegritySteps(final ScenarioState state, final JdbcClient jdbc) {
		this.state = state;
		this.jdbc = jdbc;
	}

	@Then("exactly {int} deposit(s) is/are recorded for {string} with key {string}")
	public void depositsRecorded(final int count, final String merchant, final String key) {
		Integer journals = jdbc.sql("SELECT count(*) FROM journal WHERE merchant_id = :merchant AND idempotency_key = :key")
				.param("merchant", state.merchant(merchant)).param("key", key).query(Integer.class).single();
		assertThat(journals).isEqualTo(count);
	}

	@Then("every journal sums to zero in each currency")
	public void everyJournalSumsToZero() {
		Integer unbalanced = jdbc.sql("""
				SELECT count(*) FROM (SELECT journal_id, currency FROM entry
				GROUP BY journal_id, currency HAVING sum(amount) <> 0) unbalanced""").query(Integer.class).single();
		assertThat(unbalanced).isZero();
	}

	@Then("every merchant balance equals the sum of its entries")
	public void everyMerchantBalanceEqualsItsEntries() {
		Integer off = jdbc.sql("""
				SELECT count(*) FROM account a WHERE a.type = 'MERCHANT'
				AND a.balance <> (SELECT coalesce(sum(e.amount), 0) FROM entry e WHERE e.account_id = a.id)""")
				.query(Integer.class).single();
		assertThat(off).isZero();
	}

}
