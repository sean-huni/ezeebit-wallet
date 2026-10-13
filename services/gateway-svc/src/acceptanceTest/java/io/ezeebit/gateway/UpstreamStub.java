package io.ezeebit.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

// Stands in for ledger-svc and conversion-svc behind lb://, recording exactly what the gateway forwarded.
final class UpstreamStub implements AutoCloseable {

	record Seen(String path, Map<String, List<String>> headers) {

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
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			UpstreamStub stub = new UpstreamStub(server);
			server.createContext("/", exchange -> {
				stub.seen.add(new Seen(exchange.getRequestURI().getRawPath(), Map.copyOf(exchange.getRequestHeaders())));
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, BODY.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(BODY);
				}
			});
			server.start();
			return stub;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// Both services resolve to this stub through the simple discovery client, as Eureka would resolve them.
	List<String> instances() {
		String uri = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
		return List.of("--spring.cloud.discovery.client.simple.instances.ledger-svc[0].uri=%s".formatted(uri),
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=%s".formatted(uri));
	}

	int hits() {
		return seen.size();
	}

	Seen last() {
		return seen.getLast();
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
