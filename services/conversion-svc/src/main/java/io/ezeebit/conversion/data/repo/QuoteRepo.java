package io.ezeebit.conversion.data.repo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.QuoteEntity;

public interface QuoteRepo {

	void insert(QuoteEntity quote);

	Optional<QuoteEntity> findForMerchant(UUID id, UUID merchantId);

	Optional<BigDecimal> recentRate(String sell, String buy, Instant since);

	void markUsed(UUID id);

}
