package io.ezeebit.observability;

import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;

// Sits where the agent's default wiring puts PyroscopeExporter (inside QueuedExporter), so every upload call is
// bracketed for PushRecorder on the thread that makes it.
final class RecordingExporter implements Exporter {

	private final Exporter delegate;

	private final PushRecorder recorder;

	RecordingExporter(final Exporter delegate, final PushRecorder recorder) {
		this.delegate = delegate;
		this.recorder = recorder;
	}

	@Override
	public void export(final Snapshot snapshot) {
		recorder.begin();
		boolean returned = false;
		try {
			delegate.export(snapshot);
			returned = true;
		}
		finally {
			if (!returned) {
				recorder.failed();
			}
			recorder.end();
		}
	}

	@Override
	public void stop() {
		delegate.stop();
	}
}
