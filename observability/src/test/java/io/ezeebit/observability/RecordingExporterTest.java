package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import org.junit.jupiter.api.Test;

class RecordingExporterTest {

	private static final Instant CREATED = Instant.parse("2026-11-04T10:00:00Z");

	private static final Instant LATER = CREATED.plusSeconds(60);

	private final AtomicReference<Instant> now = new AtomicReference<>(CREATED);

	private final PushRecorder recorder = new PushRecorder(new Clock() {
		@Override
		public Instant instant() {
			return now.get();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(final ZoneId zone) {
			return this;
		}
	});

	@Test
	void anAttemptMadeInsideTheUploadIsRecordedAsAPush() {
		now.set(LATER);

		new RecordingExporter(new Uploader(recorder::attempt, null), recorder).export(null);

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(LATER.getEpochSecond());
	}

	@Test
	void anUploadThatThrowsAfterItsAttemptIsNotAPush() {
		now.set(LATER);
		RecordingExporter exporter = new RecordingExporter(
				new Uploader(recorder::attempt, new IllegalStateException("boom")), recorder);

		assertThatThrownBy(() -> exporter.export(null)).isInstanceOf(IllegalStateException.class);

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(CREATED.getEpochSecond());
	}

	@Test
	void stopReachesTheUploader() {
		Uploader uploader = new Uploader(() -> { }, null);

		new RecordingExporter(uploader, recorder).stop();

		assertThat(uploader.stopped).isTrue();
	}

	private static final class Uploader implements Exporter {

		private final Runnable during;

		private final RuntimeException failure;

		private boolean stopped;

		Uploader(final Runnable during, final RuntimeException failure) {
			this.during = during;
			this.failure = failure;
		}

		@Override
		public void export(final Snapshot snapshot) {
			during.run();
			if (failure != null) {
				throw failure;
			}
		}

		@Override
		public void stop() {
			stopped = true;
		}
	}
}
