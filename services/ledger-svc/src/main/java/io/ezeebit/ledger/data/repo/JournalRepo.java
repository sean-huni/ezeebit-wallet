package io.ezeebit.ledger.data.repo;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JournalRepo {

	private final JdbcClient jdbc;

	public JournalRepo(final JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// A duplicate (merchant_id, type, idempotency_key) raises DuplicateKeyException: that is the replay signal.
	public void insert(final JournalEntity journal) {
		jdbc.sql("""
				INSERT INTO journal (id, merchant_id, type, status, idempotency_key, request_hash, reference)
				VALUES (:id, :merchantId, :type, :status, :idempotencyKey, :requestHash, :reference)""")
				.paramSource(journal)
				.update();
	}

	public Optional<JournalEntity> findByIdentity(final UUID merchantId, final String type, final String key) {
		return jdbc.sql("""
				SELECT id, merchant_id, type, status, idempotency_key, request_hash, reference FROM journal
				WHERE merchant_id = :merchantId AND type = :type AND idempotency_key = :key""")
				.param("merchantId", merchantId)
				.param("type", type)
				.param("key", key)
				.query(JournalEntity.class)
				.optional();
	}

}
