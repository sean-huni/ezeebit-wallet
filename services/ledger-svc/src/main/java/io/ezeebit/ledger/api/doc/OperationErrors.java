package io.ezeebit.ledger.api.doc;

import static io.ezeebit.ledger.exception.ErrorCode.AMOUNT_NOT_POSITIVE;
import static io.ezeebit.ledger.exception.ErrorCode.AMOUNT_SCALE_EXCEEDED;
import static io.ezeebit.ledger.exception.ErrorCode.AMOUNT_TOO_LARGE;
import static io.ezeebit.ledger.exception.ErrorCode.BALANCE_LIMIT_EXCEEDED;
import static io.ezeebit.ledger.exception.ErrorCode.CURRENCY_MISMATCH;
import static io.ezeebit.ledger.exception.ErrorCode.GATEWAY_REQUIRED;
import static io.ezeebit.ledger.exception.ErrorCode.IDEMPOTENCY_KEY_MISSING;
import static io.ezeebit.ledger.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static io.ezeebit.ledger.exception.ErrorCode.INTERNAL_ERROR;
import static io.ezeebit.ledger.exception.ErrorCode.MALFORMED_REQUEST;
import static io.ezeebit.ledger.exception.ErrorCode.METHOD_NOT_ALLOWED;
import static io.ezeebit.ledger.exception.ErrorCode.NOT_FOUND;
import static io.ezeebit.ledger.exception.ErrorCode.UNBALANCED_POSTING;
import static io.ezeebit.ledger.exception.ErrorCode.UNKNOWN_CURRENCY;
import static io.ezeebit.ledger.exception.ErrorCode.UNSUPPORTED_API_VERSION;
import static io.ezeebit.ledger.exception.ErrorCode.UNSUPPORTED_MEDIA_TYPE;
import static io.ezeebit.ledger.exception.ErrorCode.VALIDATION_FAILED;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.ezeebit.ledger.exception.ErrorCode;

// The codes each operation can answer, by operationId; ErrorStatus turns them into the spec's error responses.
// An operation missing here fails spec generation, and OpenApiContractIT plus the acceptance run hold the lists true.
public final class OperationErrors {

	// Answered for a path, method or version that no operation maps, so they belong to no operation.
	public static final Set<ErrorCode> ROUTING = Set.copyOf(EnumSet.of(NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_API_VERSION));

	private static final Set<ErrorCode> BODY = EnumSet.of(VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_MEDIA_TYPE, INTERNAL_ERROR);
	private static final Set<ErrorCode> MONEY = EnumSet.of(UNKNOWN_CURRENCY, AMOUNT_SCALE_EXCEEDED, AMOUNT_NOT_POSITIVE,
			AMOUNT_TOO_LARGE, CURRENCY_MISMATCH, UNBALANCED_POSTING);

	private static final Map<String, Set<ErrorCode>> BY_OPERATION = Map.of(
			"getBalances", EnumSet.of(VALIDATION_FAILED, GATEWAY_REQUIRED, INTERNAL_ERROR),
			"listEntries", EnumSet.of(VALIDATION_FAILED, UNKNOWN_CURRENCY, GATEWAY_REQUIRED, INTERNAL_ERROR),
			"createDeposit", union(BODY, MONEY, EnumSet.of(IDEMPOTENCY_KEY_MISSING, IDEMPOTENCY_KEY_REUSED, BALANCE_LIMIT_EXCEEDED)));

	private OperationErrors() {
	}

	public static Set<ErrorCode> of(final String operationId) {
		Set<ErrorCode> codes = BY_OPERATION.get(operationId);
		if (codes == null) {
			throw new IllegalStateException("operation %s has no entry in OperationErrors".formatted(operationId));
		}
		return EnumSet.copyOf(codes);
	}

	@SafeVarargs
	private static Set<ErrorCode> union(final Set<ErrorCode>... parts) {
		Set<ErrorCode> all = EnumSet.noneOf(ErrorCode.class);
		for (Set<ErrorCode> part : parts) {
			all.addAll(part);
		}
		return all;
	}

}
