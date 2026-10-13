package io.ezeebit.conversion.common;

import java.time.Duration;

import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.domain.Settlement;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

// Counters are flows, so each replica counts its own and dashboards sum them. Tags are bounded enums only.
@Component
public class ConversionMetrics {

	public static final String QUOTES = "ezeebit.conversion.quotes";
	public static final String RATE_CALLS = "ezeebit.conversion.rate.calls";
	public static final String OUTCOMES = "ezeebit.conversion.outcomes";
	public static final String LEFT_PENDING = "ezeebit.conversion.left.pending";
	public static final String SWEEPS = "ezeebit.conversion.sweeps";
	private static final String NONE = "none";
	// The rate client gives up at about 4 s (2 s connect, 2 s read), so the top bucket holds every call.
	private static final Duration[] RATE_CALL_BUCKETS = { Duration.ofMillis(25), Duration.ofMillis(50),
			Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
			Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5) };

	private final MeterRegistry registry;

	public ConversionMetrics(final MeterRegistry registry) {
		this.registry = registry;
	}

	public void quoteIssued(final String pair) {
		quote(pair, "issued", NONE, NONE);
	}

	// The detail is read only from a rate rejection, whose reason is always a RateRejection constant.
	public void quoteRefused(final String pair, final ConversionException refusal) {
		String detail = refusal.code() == ErrorCode.RATE_REJECTED
				? String.valueOf(refusal.properties().getOrDefault("reason", NONE)) : NONE;
		quote(pair, "refused", refusal.code().name(), detail);
	}

	public void rateCall(final Duration took, final String outcome) {
		Timer.builder(RATE_CALLS).tag("outcome", outcome).serviceLevelObjectives(RATE_CALL_BUCKETS)
				.register(registry).record(took);
	}

	public void conversion(final ConversionStatus status, final RejectionReason reason) {
		registry.counter(OUTCOMES, "status", status.name(), "reason", reason == null ? NONE : reason.name())
				.increment();
	}

	// Kept apart from OUTCOMES so that summing outcomes counts conversions: a PENDING one settles later.
	public void leftPending() {
		registry.counter(LEFT_PENDING).increment();
	}

	public void sweep(final LedgerAnswer answer) {
		registry.counter(SWEEPS, "action", answer.settlement().map(ConversionMetrics::action).orElse("unresolved"))
				.increment();
	}

	private void quote(final String pair, final String outcome, final String reason, final String detail) {
		registry.counter(QUOTES, "pair", pair, "outcome", outcome, "reason", reason, "detail", detail).increment();
	}

	private static String action(final Settlement settlement) {
		if (settlement.status() == ConversionStatus.COMPLETED) {
			return "completed";
		}
		return settlement.reason() == RejectionReason.VOIDED ? "voided" : "refused";
	}

}
