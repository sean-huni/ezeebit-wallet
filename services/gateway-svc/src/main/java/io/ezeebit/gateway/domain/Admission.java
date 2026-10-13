package io.ezeebit.gateway.domain;

import java.time.Duration;

public record Admission(LimitOutcome outcome, long remaining, Duration retryAfter) {

	public static Admission allowed(final long remaining) {
		return new Admission(LimitOutcome.ALLOWED, remaining, Duration.ZERO);
	}

	public static Admission limited(final Duration retryAfter) {
		return new Admission(LimitOutcome.LIMITED, 0, retryAfter);
	}

	public static Admission contention(final Duration retryAfter) {
		return new Admission(LimitOutcome.CONTENTION, 0, retryAfter);
	}

	public static Admission unavailable(final Duration retryAfter) {
		return new Admission(LimitOutcome.UNAVAILABLE, 0, retryAfter);
	}

}
