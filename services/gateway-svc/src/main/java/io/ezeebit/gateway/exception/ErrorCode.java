package io.ezeebit.gateway.exception;

// The wire code is the constant name; the key selects the text in i18n/messages.properties.
public enum ErrorCode {

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
