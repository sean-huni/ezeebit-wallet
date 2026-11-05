package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PushRecorderTest {

	private static final Instant CREATED = Instant.parse("2026-11-04T10:00:00Z");

	private final AtomicReference<Instant> now = new AtomicReference<>(CREATED);

	private final Clock clock = new Clock() {
		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(final ZoneId zone) {
			return this;
		}
	};

	private final PushRecorder recorder = new PushRecorder(clock);

	private void later() {
		now.set(CREATED.plusSeconds(30));
	}

	@Test
	void startsAtItsCreationSoAProfilerThatNeverPushesAges() {
		later();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.getEpochSecond());
	}

	@Test
	void aCallWhoseOnlyAttemptDidNotFailIsAPush() {
		later();
		recorder.begin();
		recorder.attempt();
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.plusSeconds(30).getEpochSecond());
	}

	@Test
	void aCallWhoseLastAttemptFailedIsNotAPush() {
		later();
		recorder.begin();
		recorder.attempt();
		recorder.failed();
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.getEpochSecond());
	}

	@Test
	void aRetryThatSucceedsAfterAFailureIsAPush() {
		later();
		recorder.begin();
		recorder.attempt();
		recorder.failed();
		recorder.attempt();
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.plusSeconds(30).getEpochSecond());
	}

	@Test
	void aCallThatMadeNoAttemptIsNotAPush() {
		later();
		recorder.begin();
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.getEpochSecond());
	}

	@Test
	void anAttemptOutsideAnExportCallIsIgnored() {
		later();
		recorder.attempt();
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.getEpochSecond());
	}
}
