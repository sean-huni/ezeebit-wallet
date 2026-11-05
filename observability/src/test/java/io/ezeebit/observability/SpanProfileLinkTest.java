package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.Test;

class SpanProfileLinkTest {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private SpanProcessor linkThatCannotLoad() {
		return SpanProfileLink.create(() -> {
			throw new UnsatisfiedLinkError("no libasyncProfiler");
		}, registry);
	}

	@Test
	void aLinkThatCannotLoadDegradesToNoOp() {
		SpanProcessor processor = linkThatCannotLoad();

		assertThat(processor.isStartRequired() || processor.isEndRequired()).isFalse();
	}

	@Test
	void aLinkThatCannotLoadIsCounted() {
		linkThatCannotLoad();

		assertThat(registry.counter("ezeebit.profiling.failures", "cause", "link-UnsatisfiedLinkError").count())
				.isEqualTo(1.0);
	}
}
