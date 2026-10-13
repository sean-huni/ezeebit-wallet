package io.ezeebit.gateway.exception;

import java.time.Duration;
import java.util.Optional;

// A request the edge refuses before routing; the advice renders it. Carries a code, never text.
public class EdgeRefusalException extends RuntimeException {

	private final ErrorCode code;
	private final Duration retryAfter;

	public EdgeRefusalException(final ErrorCode code, final Duration retryAfter, final Throwable cause) {
		super(code.name(), cause);
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public static EdgeRefusalException of(final ErrorCode code) {
		return new EdgeRefusalException(code, null, null);
	}

	public ErrorCode code() {
		return code;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(retryAfter);
	}

}
