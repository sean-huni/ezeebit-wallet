package io.ezeebit.conversion.exception;

// The wire code is the constant name; the key selects the text in i18n/messages.properties.
// No HTTP here: api/advice/ErrorStatus maps each code to its status.
public enum ErrorCode {

	VALIDATION_FAILED("error.validation.failed"),
	MALFORMED_REQUEST("error.request.malformed"),
	IDEMPOTENCY_KEY_MISSING("error.idempotency.key.missing"),
	UNKNOWN_CURRENCY("error.currency.unknown"),
	SAME_CURRENCY("error.currency.same"),
	UNSUPPORTED_PAIR("error.pair.unsupported"),
	AMOUNT_SCALE_EXCEEDED("error.amount.scale.exceeded"),
	AMOUNT_NOT_POSITIVE("error.amount.not.positive"),
	AMOUNT_TOO_LARGE("error.amount.too.large"),
	AMOUNT_TOO_SMALL("error.amount.too.small"),
	UNSUPPORTED_API_VERSION("error.api.version.unsupported"),
	GATEWAY_REQUIRED("error.gateway.required"),
	NOT_FOUND("error.not.found"),
	QUOTE_NOT_FOUND("error.quote.not.found"),
	CONVERSION_NOT_FOUND("error.conversion.not.found"),
	METHOD_NOT_ALLOWED("error.method.not.allowed"),
	QUOTE_EXPIRED("error.quote.expired"),
	QUOTE_ALREADY_USED("error.quote.already.used"),
	CONVERSION_REJECTED("error.conversion.rejected"),
	UNSUPPORTED_MEDIA_TYPE("error.media.type.unsupported"),
	IDEMPOTENCY_KEY_REUSED("error.idempotency.key.reused"),
	INSUFFICIENT_FUNDS("error.funds.insufficient"),
	BALANCE_LIMIT_EXCEEDED("error.balance.limit.exceeded"),
	INTERNAL_ERROR("error.internal"),
	RATE_REJECTED("error.rate.rejected"),
	RATE_UNAVAILABLE("error.rate.unavailable");

	private final String key;

	ErrorCode(final String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

}
