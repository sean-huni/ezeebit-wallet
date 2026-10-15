package io.ezeebit.conversion.acceptance;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// The real ledger-svc jar in its own JVM with its own database: conversion-svc reaches it over gRPC, the steps over HTTP.
final class LedgerProcess {

	private static final Duration BOOT_LIMIT = Duration.ofSeconds(90);

	private final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
			.withDatabaseName("ledger");
	private Process process;
	private int port;
	private int grpcPort;

	void start() throws IOException, InterruptedException {
		database.start();
		port = freePort();
		grpcPort = freePort();
		String jar = System.getProperty("ledger.jar");
		String java = ProcessHandle.current().info().command().orElse("java");
		ProcessBuilder builder = new ProcessBuilder(List.of(java, "-jar", jar,
				"--server.port=%d".formatted(port), "--server.address=127.0.0.1",
				"--spring.grpc.server.port=%d".formatted(grpcPort), "--spring.grpc.server.address=127.0.0.1",
				"--spring.datasource.url=%s".formatted(database.getJdbcUrl()),
				"--spring.datasource.username=%s".formatted(database.getUsername()),
				"--spring.datasource.password=%s".formatted(database.getPassword()),
				"--spring.cloud.config.enabled=false", "--eureka.client.enabled=false",
				// The gateway proof secret, from the file config-svc serves to every service.
				"--spring.config.import=file:%s".formatted(System.getProperty("served.shared")),
				"--management.otlp.metrics.export.enabled=false", "--management.tracing.export.otlp.enabled=false",
				"--management.logging.export.otlp.enabled=false"))
				.redirectErrorStream(true)
				.redirectOutput(new File("build/ledger-process.log"));
		builder.environment().put("CONFIG_IMPORT_OPTIONALITY", "optional:");
		process = builder.start();
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
		awaitHealthy();
	}

	String url() {
		return "http://127.0.0.1:%d".formatted(port);
	}

	int grpcPort() {
		return grpcPort;
	}

	PostgreSQLContainer database() {
		return database;
	}

	void stop() {
		if (process != null) {
			process.destroy();
		}
		database.stop();
	}

	private void awaitHealthy() throws InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
		Instant deadline = Instant.now().plus(BOOT_LIMIT);
		while (Instant.now().isBefore(deadline)) {
			if (!process.isAlive()) {
				throw new IllegalStateException("ledger process exited %d; see build/ledger-process.log"
						.formatted(process.exitValue()));
			}
			try {
				HttpResponse<Void> health = client.send(HttpRequest.newBuilder(URI.create("%s/actuator/health".formatted(url())))
						.build(), HttpResponse.BodyHandlers.discarding());
				if (health.statusCode() == 200) {
					return;
				}
			}
			catch (IOException notYet) {
				// Still booting.
			}
			Thread.sleep(500);
		}
		throw new IllegalStateException("ledger not healthy after %s; see build/ledger-process.log".formatted(BOOT_LIMIT));
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
