package io.ezeebit.ledger.exception;

// The wire code is the constant name; the key selects the text in i18n/messages.properties.
// No HTTP here: api/advice/ErrorStatus maps each code to its status.
public enum ErrorCode {

	VALIDATION_FAILED("error.validation.failed"),
	MALFORMED_REQUEST("error.request.malformed"),
	IDEMPOTENCY_KEY_MISSING("error.idempotency.key.missing"),
	UNKNOWN_CURRENCY("error.currency.unknown"),
	AMOUNT_SCALE_EXCEEDED("error.amount.scale.exceeded"),
	AMOUNT_NOT_POSITIVE("error.amount.not.positive"),
	AMOUNT_TOO_LARGE("error.amount.too.large"),
	UNSUPPORTED_API_VERSION("error.api.version.unsupported"),
	GATEWAY_REQUIRED("error.gateway.required"),
	NOT_FOUND("error.not.found"),
	POSTING_NOT_FOUND("error.posting.not.found"),
	METHOD_NOT_ALLOWED("error.method.not.allowed"),
	UNSUPPORTED_MEDIA_TYPE("error.media.type.unsupported"),
	SAME_CURRENCY("error.currency.same"),
	POSTING_DEADLINE_PASSED("error.posting.deadline.passed"),
	CONVERSION_VOIDED("error.conversion.voided"),
	CONVERSION_ALREADY_POSTED("error.conversion.already.posted"),
	IDEMPOTENCY_KEY_REUSED("error.idempotency.key.reused"),
	INSUFFICIENT_FUNDS("error.funds.insufficient"),
	BALANCE_LIMIT_EXCEEDED("error.balance.limit.exceeded"),
	CURRENCY_MISMATCH("error.currency.mismatch"),
	UNBALANCED_POSTING("error.posting.unbalanced"),
	INTERNAL_ERROR("error.internal");

	private final String key;

	ErrorCode(final String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

}
