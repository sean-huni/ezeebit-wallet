package io.ezeebit.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.MeterRegistry;
import io.pyroscope.javaagent.api.Logger;
import org.slf4j.LoggerFactory;

// The SDK's own logger writes every push retry to stderr, about ten lines a minute with the server down.
final class PyroscopeLogBridge implements Logger {

	private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("io.pyroscope");

	private static final Duration WINDOW = Duration.ofMinutes(1);

	// [!REPLICA-LOCAL] throttles this JVM's own log lines: per-process by design, nothing for replicas to agree on.
	private final Map<String, Instant> lastLogged = new ConcurrentHashMap<>();

	private final MeterRegistry registry;

	private final Clock clock;

	private final PushRecorder recorder;

	PyroscopeLogBridge(final MeterRegistry registry, final Clock clock, final PushRecorder recorder) {
		this.registry = registry;
		this.clock = clock;
		this.recorder = recorder;
	}

	@Override
	public void log(final Level level, final String format, final Object... args) {
		String key = String.valueOf(format);
		count(key);
		Instant now = clock.instant();
		boolean[] due = {false};
		lastLogged.compute(key, (ignored, last) -> {
			if (last == null || !now.isBefore(last.plus(WINDOW))) {
				due[0] = true;
				return now;
			}
			return last;
		});
		if (due[0]) {
			emit(level, render(key, args));
		}
	}

	private void count(final String format) {
		if (format.startsWith("Upload attempt")) {
			recorder.attempt();
		}
		else if (format.startsWith("Error uploading snapshot")) {
			recorder.failed();
			registry.counter("ezeebit.profiling.failures", "cause", "push").increment();
		}
		else if (format.startsWith("Gave up uploading")) {
			registry.counter("ezeebit.profiling.failures", "cause", "push_dropped").increment();
		}
	}

	private static String render(final String format, final Object... args) {
		String line;
		try {
			line = String.format(Locale.ROOT, format, args);
		}
		catch (IllegalFormatException malformed) {
			line = format;
		}
		return line.replace('\r', ' ').replace('\n', ' ');
	}

	private static void emit(final Level level, final String line) {
		switch (level) {
			case DEBUG -> LOG.debug("{}", line);
			case INFO -> LOG.info("{}", line);
			default -> LOG.warn("{}", line);
		}
	}
}
