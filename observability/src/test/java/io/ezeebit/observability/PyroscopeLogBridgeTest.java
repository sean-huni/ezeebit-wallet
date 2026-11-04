package io.ezeebit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pyroscope.javaagent.api.Logger.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PyroscopeLogBridgeTest {

	private static final String UPLOAD = "Error uploading snapshot: %s";

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));

	private final PushRecorder recorder = new PushRecorder(clock);

	private final PyroscopeLogBridge bridge = new PyroscopeLogBridge(registry, clock, recorder);

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private final Logger sdkLogger = (Logger) LoggerFactory.getLogger("io.pyroscope");

	private ch.qos.logback.classic.Level previousLevel;

	@BeforeEach
	void attach() {
		appender.start();
		sdkLogger.addAppender(appender);
		previousLevel = sdkLogger.getLevel();
		sdkLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
	}

	@AfterEach
	void detach() {
		sdkLogger.detachAppender(appender);
		sdkLogger.setLevel(previousLevel);
	}

	@Test
	void aRepeatedUploadErrorWithinAMinuteLogsOnceAndCountsEveryOccurrence() {
		bridge.log(Level.ERROR, UPLOAD, "Failed to connect to /127.0.0.1:1");
		clock.advance(Duration.ofSeconds(30));
		bridge.log(Level.ERROR, UPLOAD, "Failed to connect to /127.0.0.1:1");
		clock.advance(Duration.ofSeconds(29));
		bridge.log(Level.ERROR, UPLOAD, "Failed to connect to /127.0.0.1:1");

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.containsExactly("Error uploading snapshot: Failed to connect to /127.0.0.1:1");
		assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
		assertThat(registry.counter("ezeebit.profiling.failures", "cause", "push").count()).isEqualTo(3.0);
	}

	@Test
	void theSameFormatLogsAgainOnceTheMinuteHasPassed() {
		bridge.log(Level.ERROR, UPLOAD, "first");
		clock.advance(Duration.ofMinutes(1));
		bridge.log(Level.ERROR, UPLOAD, "second");

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.containsExactly("Error uploading snapshot: first", "Error uploading snapshot: second");
	}

	@Test
	void aDifferentFormatLogsSeparately() {
		bridge.log(Level.ERROR, UPLOAD, "refused");
		bridge.log(Level.ERROR, "Gave up uploading profiling snapshot after %d tries", 8);

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.containsExactly("Error uploading snapshot: refused", "Gave up uploading profiling snapshot after 8 tries");
		assertThat(registry.counter("ezeebit.profiling.failures", "cause", "push_dropped").count()).isEqualTo(1.0);
	}

	@Test
	void anUploadAttemptWithNoErrorAfterItRecordsAPush() {
		recorder.begin();
		clock.advance(Duration.ofSeconds(10));
		bridge.log(Level.DEBUG, "Upload attempt %d to %s. Profile: %s bytes", 1, "http://pyroscope:4040", 2048);
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(clock.instant().getEpochSecond());
	}

	@Test
	void anUploadErrorAfterTheAttemptRecordsNoPush() {
		double before = recorder.lastPushEpochSeconds();
		recorder.begin();
		clock.advance(Duration.ofSeconds(10));
		bridge.log(Level.DEBUG, "Upload attempt %d to %s. Profile: %s bytes", 1, "http://pyroscope:4040", 2048);
		bridge.log(Level.ERROR, UPLOAD, "refused");
		recorder.end();

		assertThat(recorder.lastPushEpochSeconds()).isEqualTo(before);
	}

	@Test
	void aLineBreakInAnArgumentCannotSplitTheLogLine() {
		bridge.log(Level.ERROR, UPLOAD, "refused\r\nforged line");

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.containsExactly("Error uploading snapshot: refused  forged line");
	}

	@Test
	void otherFormatsKeepTheirLowerLevelAndAreNotCounted() {
		bridge.log(Level.INFO, "Profiling started");
		bridge.log(Level.DEBUG, "Config: %s", "event=wall");

		assertThat(appender.list).extracting(ILoggingEvent::getLevel)
				.containsExactly(ch.qos.logback.classic.Level.INFO, ch.qos.logback.classic.Level.DEBUG);
		assertThat(registry.find("ezeebit.profiling.failures").counter()).isNull();
	}

	@Test
	void aFormatTheArgumentsDoNotFitIsLoggedAsWritten() {
		bridge.log(Level.WARN, "Invalid %d value", "not-a-number");

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("Invalid %d value");
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(final Instant start) {
			this.now = start;
		}

		void advance(final Duration step) {
			now = now.plus(step);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(final ZoneId zone) {
			return this;
		}
	}
}
