package io.ezeebit.observability;

public interface ProfilerLauncher {

	boolean running();

	void launch(ProfilingProperties properties);

	void stop();
}
