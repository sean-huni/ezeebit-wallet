package io.ezeebit.observability;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

// Agent 2.9.2 logs each upload attempt and each failure but never a success (javap of PyroscopeExporter): an
// export whose last attempt was not followed by a failure got a code below 400. Attempt state is per export call,
// on the exporting thread. [!REPLICA-LOCAL] lastSuccessMillis is this JVM's own push time: each replica reports
// its own, so there is nothing for replicas to agree on. It starts at creation, so a profiler that never pushes ages.
final class PushRecorder {

	private final Clock clock;

	private final AtomicLong lastSuccessMillis;

	private final ThreadLocal<boolean[]> call = new ThreadLocal<>();

	PushRecorder(final Clock clock) {
		this.clock = clock;
		this.lastSuccessMillis = new AtomicLong(clock.millis());
	}

	void begin() {
		call.set(new boolean[] {false, false});
	}

	void attempt() {
		boolean[] state = call.get();
		if (state != null) {
			state[0] = true;
			state[1] = false;
		}
	}

	void failed() {
		boolean[] state = call.get();
		if (state != null) {
			state[1] = true;
		}
	}

	void end() {
		boolean[] state = call.get();
		call.remove();
		if (state != null && state[0] && !state[1]) {
			lastSuccessMillis.set(clock.millis());
		}
	}

	double lastPushEpochSeconds() {
		return lastSuccessMillis.get() / 1000.0;
	}
}
