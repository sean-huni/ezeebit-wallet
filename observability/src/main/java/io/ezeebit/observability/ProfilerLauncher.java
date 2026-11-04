package io.ezeebit.observability;

@FunctionalInterface
public interface ProfilerLauncher {

	void launch(ProfilingProperties properties);
}
