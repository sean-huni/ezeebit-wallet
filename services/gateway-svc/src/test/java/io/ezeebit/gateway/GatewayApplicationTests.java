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
class GatewayApplicationTests {

	private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() {
	};

	// The kernel completes the handshake from the backlog; nothing ever accepts, reads or answers.
	private static final ServerSocket SILENT = silentSocket();

	@LocalServerPort
	private int port;

	// One property source for the whole list: Boot binds an indexed list from a single source only.
	@DynamicPropertySource
	static void routes(final DynamicPropertyRegistry registry) {
		route(registry, 0, "lb://ledger-svc", "/api/v1/merchants/*/balances/**");
		route(registry, 1, "http://localhost:1", "/api/v1/merchants/*/quotes/**");
		route(registry, 2, "http://127.0.0.1:" + SILENT.getLocalPort(), "/api/v1/merchants/*/conversions/**");
	}

	private static void route(final DynamicPropertyRegistry registry, final int index, final String uri,
			final String path) {
		String prefix = "spring.cloud.gateway.server.webmvc.routes[%d].".formatted(index);
		registry.add(prefix + "id", () -> "route" + index);
		registry.add(prefix + "uri", () -> uri);
		registry.add(prefix + "predicates[0]", () -> "Path=" + path);
	}

	@AfterAll
	static void closeSilent() throws IOException {
		SILENT.close();
	}

	@Test
	void unregisteredUpstreamAnswers503() {
		assertProblem(fetch("/api/v1/merchants/m1/balances"), 503, "UPSTREAM_UNAVAILABLE");
	}

	@Test
	void unreachableUpstreamAnswers503() {
		assertProblem(fetch("/api/v1/merchants/m1/quotes"), 503, "UPSTREAM_UNAVAILABLE");
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void upstreamThatNeverAnswersIs504WithNoRetryAdvice() {
		Reply reply = fetch("/api/v1/merchants/m1/conversions/c1");

		assertProblem(reply, 504, "UPSTREAM_OUTCOME_UNKNOWN");
		assertThat((String) reply.body().get("detail")).doesNotContain("Idempotency-Key");
	}

	private static void assertProblem(final Reply reply, final int status, final String code) {
		assertThat(reply.status()).as(String.valueOf(reply.body())).isEqualTo(status);
		assertThat(reply.body()).containsEntry("status", status).containsEntry("code", code);
		assertThat((String) reply.body().get("detail")).isNotBlank();
	}

	private Reply fetch(final String uri) {
		return RestClient.create("http://localhost:" + port).get()
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
