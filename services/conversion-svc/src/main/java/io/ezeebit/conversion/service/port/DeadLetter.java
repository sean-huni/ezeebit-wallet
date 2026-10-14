package io.ezeebit.conversion.service.port;

import java.time.Instant;
import java.util.UUID;

public record DeadLetter(String op, UUID conversionId, UUID merchantId, String request, int attempts, String lastStatus,
		String lastReason, String reason, Instant at, String traceId) {
}
