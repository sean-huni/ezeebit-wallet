package io.ezeebit.conversion.api.doc;

import static io.ezeebit.conversion.exception.ErrorCode.AMOUNT_NOT_POSITIVE;
import static io.ezeebit.conversion.exception.ErrorCode.AMOUNT_SCALE_EXCEEDED;
import static io.ezeebit.conversion.exception.ErrorCode.AMOUNT_TOO_LARGE;
import static io.ezeebit.conversion.exception.ErrorCode.AMOUNT_TOO_SMALL;
import static io.ezeebit.conversion.exception.ErrorCode.BALANCE_LIMIT_EXCEEDED;
import static io.ezeebit.conversion.exception.ErrorCode.CONVERSION_NOT_FOUND;
import static io.ezeebit.conversion.exception.ErrorCode.CONVERSION_REJECTED;
import static io.ezeebit.conversion.exception.ErrorCode.GATEWAY_REQUIRED;
import static io.ezeebit.conversion.exception.ErrorCode.IDEMPOTENCY_KEY_MISSING;
import static io.ezeebit.conversion.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static io.ezeebit.conversion.exception.ErrorCode.INSUFFICIENT_FUNDS;
import static io.ezeebit.conversion.exception.ErrorCode.INTERNAL_ERROR;
import static io.ezeebit.conversion.exception.ErrorCode.MALFORMED_REQUEST;
import static io.ezeebit.conversion.exception.ErrorCode.METHOD_NOT_ALLOWED;
import static io.ezeebit.conversion.exception.ErrorCode.NOT_FOUND;
import static io.ezeebit.conversion.exception.ErrorCode.QUOTE_ALREADY_USED;
import static io.ezeebit.conversion.exception.ErrorCode.QUOTE_EXPIRED;
import static io.ezeebit.conversion.exception.ErrorCode.QUOTE_NOT_FOUND;
import static io.ezeebit.conversion.exception.ErrorCode.RATE_REJECTED;
import static io.ezeebit.conversion.exception.ErrorCode.RATE_UNAVAILABLE;
import static io.ezeebit.conversion.exception.ErrorCode.SAME_CURRENCY;
import static io.ezeebit.conversion.exception.ErrorCode.UNKNOWN_CURRENCY;
import static io.ezeebit.conversion.exception.ErrorCode.UNSUPPORTED_API_VERSION;
import static io.ezeebit.conversion.exception.ErrorCode.UNSUPPORTED_MEDIA_TYPE;
import static io.ezeebit.conversion.exception.ErrorCode.UNSUPPORTED_PAIR;
import static io.ezeebit.conversion.exception.ErrorCode.VALIDATION_FAILED;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.ezeebit.conversion.exception.ErrorCode;

// The codes each operation can answer, by operationId; ErrorStatus turns them into the spec's error responses.
// An operation missing here fails spec generation, and OpenApiContractIT plus the acceptance run hold the lists true.
public final class OperationErrors {

	// Answered for a path, method or version that no operation maps, so they belong to no operation.
	public static final Set<ErrorCode> ROUTING = Set.copyOf(EnumSet.of(NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_API_VERSION));

	private static final Map<String, Set<ErrorCode>> BY_OPERATION = Map.of(
			"createQuote", EnumSet.of(VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_MEDIA_TYPE, GATEWAY_REQUIRED, UNKNOWN_CURRENCY,
					SAME_CURRENCY, UNSUPPORTED_PAIR, AMOUNT_SCALE_EXCEEDED, AMOUNT_NOT_POSITIVE, AMOUNT_TOO_LARGE, AMOUNT_TOO_SMALL,
					RATE_REJECTED, RATE_UNAVAILABLE, INTERNAL_ERROR),
			"createConversion", EnumSet.of(VALIDATION_FAILED, MALFORMED_REQUEST, UNSUPPORTED_MEDIA_TYPE, IDEMPOTENCY_KEY_MISSING,
					GATEWAY_REQUIRED, QUOTE_NOT_FOUND, QUOTE_EXPIRED, QUOTE_ALREADY_USED, CONVERSION_REJECTED, IDEMPOTENCY_KEY_REUSED,
					INSUFFICIENT_FUNDS, BALANCE_LIMIT_EXCEEDED, INTERNAL_ERROR),
			"getConversion", EnumSet.of(VALIDATION_FAILED, GATEWAY_REQUIRED, CONVERSION_NOT_FOUND, INTERNAL_ERROR));

	private OperationErrors() {
	}

	public static Set<ErrorCode> of(final String operationId) {
		Set<ErrorCode> codes = BY_OPERATION.get(operationId);
		if (codes == null) {
			throw new IllegalStateException("operation %s has no entry in OperationErrors".formatted(operationId));
		}
		return EnumSet.copyOf(codes);
	}

}
