package io.ezeebit.conversion.api.advice;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import io.ezeebit.conversion.exception.ErrorCode;
import org.junit.jupiter.api.Test;

// The table is the statuses each code answered with before the mapping left the domain: a change is a contract change.
class ErrorStatusTest {

	private static final Map<ErrorCode, Integer> PUBLISHED = Map.ofEntries(
			entry(ErrorCode.VALIDATION_FAILED, 400),
			entry(ErrorCode.MALFORMED_REQUEST, 400),
			entry(ErrorCode.IDEMPOTENCY_KEY_MISSING, 400),
			entry(ErrorCode.UNKNOWN_CURRENCY, 400),
			entry(ErrorCode.SAME_CURRENCY, 400),
			entry(ErrorCode.UNSUPPORTED_PAIR, 400),
			entry(ErrorCode.AMOUNT_SCALE_EXCEEDED, 400),
			entry(ErrorCode.AMOUNT_NOT_POSITIVE, 400),
			entry(ErrorCode.AMOUNT_TOO_LARGE, 400),
			entry(ErrorCode.AMOUNT_TOO_SMALL, 400),
			entry(ErrorCode.UNSUPPORTED_API_VERSION, 400),
			entry(ErrorCode.GATEWAY_REQUIRED, 403),
			entry(ErrorCode.NOT_FOUND, 404),
			entry(ErrorCode.QUOTE_NOT_FOUND, 404),
			entry(ErrorCode.CONVERSION_NOT_FOUND, 404),
			entry(ErrorCode.METHOD_NOT_ALLOWED, 405),
			entry(ErrorCode.QUOTE_EXPIRED, 409),
			entry(ErrorCode.QUOTE_ALREADY_USED, 409),
			entry(ErrorCode.CONVERSION_REJECTED, 409),
			entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, 415),
			entry(ErrorCode.IDEMPOTENCY_KEY_REUSED, 422),
			entry(ErrorCode.INSUFFICIENT_FUNDS, 422),
			entry(ErrorCode.BALANCE_LIMIT_EXCEEDED, 422),
			entry(ErrorCode.INTERNAL_ERROR, 500),
			entry(ErrorCode.RATE_REJECTED, 502),
			entry(ErrorCode.RATE_UNAVAILABLE, 503));

	@Test
	void everyCodeAnswersWithItsPublishedStatus() {
		Map<ErrorCode, Integer> mapped = Arrays.stream(ErrorCode.values())
				.collect(Collectors.toMap(code -> code, code -> ErrorStatus.of(code).value()));

		assertThat(mapped).isEqualTo(PUBLISHED);
	}

}
