package io.ezeebit.conversion.exception;

// The wire code is the constant name; the key selects the text in i18n/messages.properties.
public enum ErrorCode {

	VALIDATION_FAILED("error.validation.failed", 400),
	MALFORMED_REQUEST("error.request.malformed", 400),
	IDEMPOTENCY_KEY_MISSING("error.idempotency.key.missing", 400),
	UNKNOWN_CURRENCY("error.currency.unknown", 400),
	SAME_CURRENCY("error.currency.same", 400),
	UNSUPPORTED_PAIR("error.pair.unsupported", 400),
	AMOUNT_SCALE_EXCEEDED("error.amount.scale.exceeded", 400),
	AMOUNT_NOT_POSITIVE("error.amount.not.positive", 400),
	AMOUNT_TOO_LARGE("error.amount.too.large", 400),
	AMOUNT_TOO_SMALL("error.amount.too.small", 400),
	UNSUPPORTED_API_VERSION("error.api.version.unsupported", 400),
	NOT_FOUND("error.not.found", 404),
	QUOTE_NOT_FOUND("error.quote.not.found", 404),
	CONVERSION_NOT_FOUND("error.conversion.not.found", 404),
	METHOD_NOT_ALLOWED("error.method.not.allowed", 405),
	QUOTE_EXPIRED("error.quote.expired", 409),
	QUOTE_ALREADY_USED("error.quote.already.used", 409),
	CONVERSION_REJECTED("error.conversion.rejected", 409),
	UNSUPPORTED_MEDIA_TYPE("error.media.type.unsupported", 415),
	IDEMPOTENCY_KEY_REUSED("error.idempotency.key.reused", 422),
	INSUFFICIENT_FUNDS("error.funds.insufficient", 422),
	BALANCE_LIMIT_EXCEEDED("error.balance.limit.exceeded", 422),
	INTERNAL_ERROR("error.internal", 500),
	RATE_REJECTED("error.rate.rejected", 502),
	RATE_UNAVAILABLE("error.rate.unavailable", 503);

	private final String key;
	private final int status;

	ErrorCode(final String key, final int status) {
		this.key = key;
		this.status = status;
	}

	public String key() {
		return key;
	}

	public int status() {
		return status;
	}

}
