package io.ezeebit.gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

// The rate-limit store, real, on the image compose runs (redis.image from gradle.properties). Each container gets
// a random password, so a foreign Redis on a reused port cannot answer for it.
final class RedisStore {

	private static final int REDIS_PORT = 6379;

	private final GenericContainer<?> container;
	private final String password = UUID.randomUUID().toString();

	private RedisStore() {
		container = new GenericContainer<>(DockerImageName.parse(System.getProperty("redis.image")))
				.withExposedPorts(REDIS_PORT)
				.withCommand("redis-server", "--requirepass", password);
	}

	static RedisStore started() {
		RedisStore store = new RedisStore();
		store.container.start();
		return store;
	}

	// Bound to a fixed host port and NOT started: the gateway can boot against an address that later answers.
	static RedisStore onFixedPort() {
		RedisStore store = new RedisStore();
		store.container.setPortBindings(List.of("%d:%d".formatted(freePort(), REDIS_PORT)));
		return store;
	}

	static int freePort() {
		try (ServerSocket probe = new ServerSocket(0)) {
			return probe.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	int fixedPort() {
		return Integer.parseInt(container.getPortBindings().getFirst().split(":")[0]);
	}

	List<String> fixedArgs() {
		return List.of("--ezeebit.gateway.rate-limit.store.host=127.0.0.1",
				"--ezeebit.gateway.rate-limit.store.port=%d".formatted(fixedPort()),
				"--ezeebit.gateway.rate-limit.store.password=%s".formatted(password));
	}

	void start() {
		if (container.getContainerId() == null) {
			container.start();
		}
		else {
			container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
		}
	}

	Map<String, String> properties() {
		return Map.of("ezeebit.gateway.rate-limit.store.host", container.getHost(),
				"ezeebit.gateway.rate-limit.store.port", Integer.toString(container.getMappedPort(REDIS_PORT)),
				"ezeebit.gateway.rate-limit.store.password", password);
	}

	List<String> args() {
		return properties().entrySet().stream().map(entry -> "--%s=%s".formatted(entry.getKey(), entry.getValue())).toList();
	}

	// The store's own answer through redis-cli, not through a client this suite configured; an error reply fails.
	String cli(final String... command) {
		List<String> line = new ArrayList<>(List.of("redis-cli", "-a", password, "--no-auth-warning"));
		line.addAll(List.of(command));
		try {
			Container.ExecResult result = container.execInContainer(line.toArray(String[]::new));
			String reply = result.getStdout().strip();
			if (result.getExitCode() != 0 || reply.startsWith("ERR") || reply.startsWith("NOAUTH") || reply.contains("WRONGPASS")) {
				throw new IllegalStateException("redis-cli %s: exit %d, %s %s".formatted(String.join(" ", command),
						result.getExitCode(), reply, result.getStderr()));
			}
			return reply;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	// SIGTERM with a 1s grace: the socket drops, as when the store's pod is evicted.
	void kill() {
		container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(1).exec();
	}

	// Frozen, socket open, nothing answers: what a partition looks like to the client.
	void pause() {
		container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
	}

	void unpause() {
		container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
	}

	void stop() {
		container.stop();
	}

}
