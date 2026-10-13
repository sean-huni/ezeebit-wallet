package io.ezeebit.conversion.api.advice;

import io.ezeebit.conversion.exception.ErrorCode;
import org.springframework.http.HttpStatus;

// The one place a code meets HTTP: the domain throws codes, the web layer picks the status.
// No default: a new code does not compile until it is given a status here.
public final class ErrorStatus {

	private ErrorStatus() {
	}

	public static HttpStatus of(final ErrorCode code) {
		return switch (code) {
			case VALIDATION_FAILED, MALFORMED_REQUEST, IDEMPOTENCY_KEY_MISSING, UNKNOWN_CURRENCY, SAME_CURRENCY,
					UNSUPPORTED_PAIR, AMOUNT_SCALE_EXCEEDED, AMOUNT_NOT_POSITIVE, AMOUNT_TOO_LARGE, AMOUNT_TOO_SMALL,
					UNSUPPORTED_API_VERSION -> HttpStatus.BAD_REQUEST;
			case GATEWAY_REQUIRED -> HttpStatus.FORBIDDEN;
			case NOT_FOUND, QUOTE_NOT_FOUND, CONVERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
			case QUOTE_EXPIRED, QUOTE_ALREADY_USED, CONVERSION_REJECTED -> HttpStatus.CONFLICT;
			case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case IDEMPOTENCY_KEY_REUSED, INSUFFICIENT_FUNDS, BALANCE_LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
			case RATE_REJECTED -> HttpStatus.BAD_GATEWAY;
			case RATE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
		};
	}

}
