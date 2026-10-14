package io.ezeebit.conversion.service.port;

import java.util.UUID;

import io.ezeebit.conversion.domain.LedgerAnswer;

// What the service layer needs from a ledger, in this context's words. Every transport failure is UNKNOWN.
public interface LedgerPostings {

	LedgerAnswer post(LedgerPosting posting);

	LedgerAnswer lookup(UUID conversionId, UUID merchantId);

	LedgerAnswer voidPosting(UUID conversionId, UUID merchantId);

}
