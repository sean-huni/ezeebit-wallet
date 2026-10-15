package io.ezeebit.ledger.api.advice;

import io.ezeebit.ledger.exception.ErrorCode;
import org.springframework.http.HttpStatus;

// The one place a code meets HTTP: the domain throws codes, the web layer picks the status.
// No default: a new code does not compile until it is given a status here.
public final class ErrorStatus {

	private ErrorStatus() {
	}

	public static HttpStatus of(final ErrorCode code) {
		return switch (code) {
			case VALIDATION_FAILED, MALFORMED_REQUEST, IDEMPOTENCY_KEY_MISSING, UNKNOWN_CURRENCY,
					AMOUNT_SCALE_EXCEEDED, AMOUNT_NOT_POSITIVE, AMOUNT_TOO_LARGE, UNSUPPORTED_API_VERSION,
					SAME_CURRENCY -> HttpStatus.BAD_REQUEST;
			case GATEWAY_REQUIRED -> HttpStatus.FORBIDDEN;
			case NOT_FOUND, POSTING_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
			case POSTING_DEADLINE_PASSED, CONVERSION_VOIDED, CONVERSION_ALREADY_POSTED -> HttpStatus.CONFLICT;
			case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_FUNDS, BALANCE_LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case CURRENCY_MISMATCH, UNBALANCED_POSTING, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

}
