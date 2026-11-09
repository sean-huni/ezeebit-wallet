package io.ezeebit.ledger.data.repo;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;

public interface JournalRepo {

	void insert(JournalEntity journal);

	Optional<JournalEntity> findByIdentity(UUID merchantId, String type, String key);

}
