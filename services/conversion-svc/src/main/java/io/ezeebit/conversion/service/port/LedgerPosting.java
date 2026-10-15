package io.ezeebit.conversion.service.port;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.domain.Quote;

public record LedgerPosting(UUID conversionId, UUID merchantId, Quote quote, Instant deadline) {
}
