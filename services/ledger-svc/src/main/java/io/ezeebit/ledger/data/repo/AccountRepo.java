package io.ezeebit.ledger.data.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepo {

	private static final String COLUMNS = "id, merchant_id, currency, type, balance";

	private final JdbcClient jdbc;

	public AccountRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// Two first deposits racing: the loser's insert waits for the winner, then does nothing.
	public void openMerchant(final UUID id, final UUID merchantId, final String currency) {
		jdbc.sql("""
				INSERT INTO account (id, merchant_id, currency, type, balance)
				VALUES (:id, :merchantId, :currency, 'MERCHANT', 0)
				ON CONFLICT (merchant_id, currency, type) DO NOTHING""")
				.param("id", id)
				.param("merchantId", merchantId)
				.param("currency", currency)
				.update();
	}

	// Serialises every posting to this merchant account; the balance is read only after the lock.
	public AccountEntity lockMerchant(final UUID merchantId, final String currency) {
		return jdbc.sql("SELECT %s FROM account WHERE merchant_id = :merchantId AND currency = :currency AND type = 'MERCHANT' FOR UPDATE"
				.formatted(COLUMNS))
				.param("merchantId", merchantId)
				.param("currency", currency)
				.query(AccountEntity.class)
				.single();
	}

	public UUID platformAccount(final String currency, final String type) {
		return jdbc.sql("SELECT id FROM account WHERE merchant_id IS NULL AND currency = :currency AND type = :type")
				.param("currency", currency)
				.param("type", type)
				.query(UUID.class)
				.single();
	}

	public void setBalance(final UUID id, final BigDecimal balance) {
		jdbc.sql("UPDATE account SET balance = :balance WHERE id = :id")
				.param("balance", balance)
				.param("id", id)
				.update();
	}

	public List<AccountEntity> merchantAccounts(final UUID merchantId) {
		return jdbc.sql("SELECT %s FROM account WHERE merchant_id = :merchantId AND type = 'MERCHANT' ORDER BY currency"
				.formatted(COLUMNS))
				.param("merchantId", merchantId)
				.query(AccountEntity.class)
				.list();
	}

}
