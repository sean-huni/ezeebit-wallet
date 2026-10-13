package io.ezeebit.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

// The rate-limit store, real, on the image compose runs (redis.image from gradle.properties). Each container gets
// a random password, so a foreign Redis on a reused port cannot answer for it.
final class RedisStore {

	private static final int REDIS_PORT = 6379;
	private static final String DOWN_FLAG = "/tmp/down";
	private static final Duration SETTLE = Duration.ofSeconds(10);

	private final GenericContainer<?> container;
	private final String password = UUID.randomUUID().toString();

	private RedisStore() {
		container = new GenericContainer<>(DockerImageName.parse(System.getProperty("redis.image")))
				.withExposedPorts(REDIS_PORT)
				.withCommand("redis-server", "--requirepass", password);
	}

	// A shell keeps the container, and so its port, alive; Redis runs under it unless the down flag exists.
	private RedisStore(final String downFlag) {
		String supervisor = "while :; do [ -e %s ] || redis-server --requirepass %s; sleep 0.1; done"
				.formatted(downFlag, password);
		container = new GenericContainer<>(DockerImageName.parse(System.getProperty("redis.image")))
				.withExposedPorts(REDIS_PORT)
				.withCommand("sh", "-c", supervisor)
				.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
	}

	static RedisStore started() {
		RedisStore store = new RedisStore();
		store.container.start();
		return store;
	}

	// Returned DOWN, on a port Docker keeps for the container's whole life. A port picked free and bound later, or
	// freed by a stopped container, can be taken by another process on 127.0.0.1 while Redis stays up behind it.
	static RedisStore held() {
		RedisStore store = new RedisStore(DOWN_FLAG);
		store.container.start();
		store.drop();
		return store;
	}

	// Returns once the store itself answers PONG, so a caller's clock starts at a known-up store.
	void serve() {
		exec("rm", "-f", DOWN_FLAG);
		await(true);
	}

	// The server exits and its sockets drop, as when the store's pod is evicted; the host port stays held.
	void drop() {
		exec("touch", DOWN_FLAG);
		exec("redis-cli", "-a", password, "--no-auth-warning", "shutdown", "nosave");
		await(false);
	}

	Map<String, String> properties() {
		return Map.of("ezeebit.gateway.rate-limit.store.host", container.getHost(),
				"ezeebit.gateway.rate-limit.store.port", Integer.toString(container.getMappedPort(REDIS_PORT)),
				"ezeebit.gateway.rate-limit.store.password", password);
	}

	List<String> args() {
		return properties().entrySet().stream().map(entry -> "--%s=%s".formatted(entry.getKey(), entry.getValue())).toList();
	}

	private void await(final boolean up) {
		long deadline = System.nanoTime() + SETTLE.toNanos();
		while (System.nanoTime() - deadline < 0) {
			boolean pong = "PONG".equals(exec("redis-cli", "-a", password, "--no-auth-warning", "ping").getStdout().strip());
			if (pong == up) {
				return;
			}
			sleepQuietly();
		}
		throw new IllegalStateException("store did not turn %s within %s".formatted(up ? "up" : "down", SETTLE));
	}

	private Container.ExecResult exec(final String... command) {
		try {
			return container.execInContainer(command);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static void sleepQuietly() {
		try {
			Thread.sleep(Duration.ofMillis(50));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
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
