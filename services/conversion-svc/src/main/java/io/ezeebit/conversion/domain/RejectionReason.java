package io.ezeebit.conversion.domain;

import io.ezeebit.conversion.exception.ErrorCode;

// Why a conversion ended REJECTED. The first three are refusals the ledger made final; the last two mean nothing
// was posted by the deadline, or the sweeper voided it.
public enum RejectionReason {

	INSUFFICIENT_FUNDS, BALANCE_LIMIT_EXCEEDED, INPUT_REFUSED, DEADLINE_PASSED, VOIDED;

	public ErrorCode answer() {
		return switch (this) {
			case INSUFFICIENT_FUNDS -> ErrorCode.INSUFFICIENT_FUNDS;
			case BALANCE_LIMIT_EXCEEDED -> ErrorCode.BALANCE_LIMIT_EXCEEDED;
			case INPUT_REFUSED, DEADLINE_PASSED, VOIDED -> ErrorCode.CONVERSION_REJECTED;
		};
	}

}
