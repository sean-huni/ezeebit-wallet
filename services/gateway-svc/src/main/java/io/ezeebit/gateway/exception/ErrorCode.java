package io.ezeebit.gateway.exception;

// The wire code is the constant name; the key selects the text in i18n/messages.properties.
public enum ErrorCode {

	MALFORMED_PATH("error.path.malformed", 400),
	INVALID_MERCHANT_ID("error.merchant.id.invalid", 400),
	RATE_LIMITED("error.rate.limited", 429),
	RATE_LIMITER_UNAVAILABLE("error.rate.limiter.unavailable", 503),
	UPSTREAM_UNAVAILABLE("error.upstream.unavailable", 503),
	UPSTREAM_OUTCOME_UNKNOWN("error.upstream.outcome.unknown", 504);

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
