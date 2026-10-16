package io.ezeebit.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

// Stands in for ledger-svc and conversion-svc behind lb://, recording exactly what the gateway forwarded.
final class UpstreamStub implements AutoCloseable {

	record Seen(String path, Map<String, List<String>> headers, String body) {

		List<String> header(final String name) {
			return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
					.flatMap(entry -> entry.getValue().stream()).toList();
		}

	}

	private static final byte[] BODY = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);

	private final HttpServer server;
	private final List<Seen> seen = new CopyOnWriteArrayList<>();

	private UpstreamStub(final HttpServer server) {
		this.server = server;
	}

	static UpstreamStub start() {
		return start(200, Duration.ZERO);
	}

	static UpstreamStub start(final int status, final Duration delay) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			UpstreamStub stub = new UpstreamStub(server);
			server.createContext("/", exchange -> {
				stub.seen.add(new Seen(exchange.getRequestURI().getRawPath(), Map.copyOf(exchange.getRequestHeaders()),
						new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
				pause(delay);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, BODY.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(BODY);
				}
			});
			// One thread per exchange: a delayed answer must not hold back recording the next request that arrives.
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			return stub;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void pause(final Duration delay) {
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	String uri() {
		return "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
	}

	// Both services resolve to this stub through the simple discovery client, as Eureka would resolve them.
	List<String> instances() {
		String uri = uri();
		return List.of("--spring.cloud.discovery.client.simple.instances.ledger-svc[0].uri=%s".formatted(uri),
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=%s".formatted(uri));
	}

	int hits() {
		return seen.size();
	}

	List<Seen> seen() {
		return List.copyOf(seen);
	}

	Seen last() {
		return seen.getLast();
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
