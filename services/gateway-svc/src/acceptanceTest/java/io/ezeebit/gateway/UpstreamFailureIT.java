package io.ezeebit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.http.clients.read-timeout=500ms")
class UpstreamFailureIT {

	private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() {
	};

	private static final String MERCHANT = "7f9c2a1e-3b4d-4c5e-8f6a-0b1c2d3e4f5a";

	// The kernel completes the handshake from the backlog; nothing ever accepts, reads or answers.
	private static final ServerSocket SILENT = silentSocket();
	private static final RedisStore STORE = RedisStore.started();

	@LocalServerPort
	private int port;

	// One property source for the whole list: Boot binds an indexed list from a single source only.
	@DynamicPropertySource
	static void routes(final DynamicPropertyRegistry registry) {
		STORE.properties().forEach((name, value) -> registry.add(name, () -> value));
		route(registry, 0, "lb://ledger-svc", "/api/v1/merchants/*/balances/**");
		route(registry, 1, "http://localhost:1", "/api/v1/merchants/*/quotes/**");
		route(registry, 2, "http://127.0.0.1:%d".formatted(SILENT.getLocalPort()), "/api/v1/merchants/*/conversions/**");
	}

	private static void route(final DynamicPropertyRegistry registry, final int index, final String uri,
			final String path) {
		String prefix = "spring.cloud.gateway.server.webmvc.routes[%d].".formatted(index);
		registry.add("%sid".formatted(prefix), () -> "route%d".formatted(index));
		registry.add("%suri".formatted(prefix), () -> uri);
		registry.add("%spredicates[0]".formatted(prefix), () -> "Path=%s".formatted(path));
	}

	@AfterAll
	static void closeSilent() throws IOException {
		SILENT.close();
		STORE.stop();
	}

	@Test
	void unregisteredUpstreamAnswers503() {
		assertProblem(fetch("/api/v1/merchants/%s/balances".formatted(MERCHANT)), 503, "UPSTREAM_UNAVAILABLE");
	}

	@Test
	void unreachableUpstreamAnswers503() {
		assertProblem(fetch("/api/v1/merchants/%s/quotes".formatted(MERCHANT)), 503, "UPSTREAM_UNAVAILABLE");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void upstreamThatNeverAnswersIs504WithNoRetryAdvice() {
		Reply reply = fetch("/api/v1/merchants/%s/conversions/c1".formatted(MERCHANT));

		assertProblem(reply, 504, "UPSTREAM_OUTCOME_UNKNOWN");
		assertThat((String) reply.body().get("detail")).doesNotContain("Idempotency-Key");
	}

	private static void assertProblem(final Reply reply, final int status, final String code) {
		assertThat(reply.status()).as(String.valueOf(reply.body())).isEqualTo(status);
		assertThat(reply.body()).containsEntry("status", status).containsEntry("code", code);
		assertThat((String) reply.body().get("detail")).isNotBlank();
	}

	private Reply fetch(final String uri) {
		return RestClient.create("http://localhost:%d".formatted(port)).get()
				.uri(uri)
				.exchange((request, response) -> new Reply(response.getStatusCode().value(), response.bodyTo(PROBLEM)));
	}

	private static ServerSocket silentSocket() {
		try {
			return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record Reply(int status, Map<String, Object> body) {
	}

}
