package io.ezeebit.conversion.domain;

// Why a conversion ended REJECTED: the ledger refused it, or nothing was posted by its deadline and it was voided.
public enum RejectionReason {
	INSUFFICIENT_FUNDS, DEADLINE_PASSED, VOIDED
}
