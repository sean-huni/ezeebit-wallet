package io.ezeebit.conversion.data.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.ConversionEntity;

public interface ConversionRepo {

	void insertPending(UUID id, UUID merchantId, UUID quoteId, String key, Instant deadline);

	Optional<ConversionEntity> findByKey(UUID merchantId, String key);

	Optional<ConversionEntity> find(UUID id, UUID merchantId);

	int settle(UUID id, String status, String reason, Instant completed);

	Optional<ConversionEntity> claimDue(Instant cutoff, List<UUID> skip);

	long countDue(Instant cutoff);

}
