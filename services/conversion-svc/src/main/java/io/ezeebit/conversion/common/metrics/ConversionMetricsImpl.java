package io.ezeebit.conversion.common.metrics;

import java.time.Duration;

import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.exception.ConversionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

// Counters are flows, so each replica counts its own and dashboards sum them. Tags are bounded enums only.
@Component
public class ConversionMetricsImpl implements ConversionMetrics {

	// Spring's JDK read timeout starts at send and so covers connect: 2 s an attempt, 4.2 s for two plus 200 ms.
	private static final Duration[] RATE_CALL_BUCKETS = { Duration.ofMillis(25), Duration.ofMillis(50),
			Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
			Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5) };

	private final MeterRegistry registry;
	private final ConversionSpans spans;

	public ConversionMetricsImpl(final MeterRegistry registry, final ObservationRegistry observations) {
		this.registry = registry;
		this.spans = new ConversionSpansImpl(observations);
	}

	// The business spans travel with the counters, so a service records both through its one telemetry dependency.
	@Override
	public ConversionSpans spans() {
		return spans;
	}

	@Override
	public void quoteIssued(final String pair) {
		quote(pair, "issued", NONE, NONE);
	}

	// The detail is read only from a rate rejection, whose reason is always a RateRejection constant.
	@Override
	public void quoteRefused(final String pair, final ConversionException refusal) {
		quote(pair, "refused", refusal.code().name(), ConversionMetrics.detail(refusal));
	}

	@Override
	public void rateCall(final Duration took, final String outcome) {
		Timer.builder(RATE_CALLS).tag("outcome", outcome).serviceLevelObjectives(RATE_CALL_BUCKETS)
				.register(registry).record(took);
	}

	@Override
	public void conversion(final ConversionStatus status, final RejectionReason reason) {
		registry.counter(OUTCOMES, "status", status.name(), "reason", reason == null ? NONE : reason.name())
				.increment();
	}

	// Kept apart from OUTCOMES so that summing outcomes counts conversions: a PENDING one settles later.
	@Override
	public void leftPending() {
		registry.counter(LEFT_PENDING).increment();
	}

	@Override
	public void sweep(final LedgerAnswer answer) {
		registry.counter(SWEEPS, "action", ConversionMetrics.sweepAction(answer)).increment();
	}

	@Override
	public void deadLettered(final String op, final String reason) {
		registry.counter(DEAD_LETTERS, "op", op, "reason", reason).increment();
	}

	@Override
	public void deadLetterLost(final String op) {
		registry.counter(DEAD_LETTER_FAILURES, "op", op).increment();
	}

	private void quote(final String pair, final String outcome, final String reason, final String detail) {
		registry.counter(QUOTES, "pair", pair, "outcome", outcome, "reason", reason, "detail", detail).increment();
	}

}
