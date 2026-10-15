package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

import io.ezeebit.gateway.api.filter.RateLimitFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// One Swagger UI on the gateway over both public specs; each spec is fetched from its service as the gateway, uncharged.
class ApiDocsIT {

	private static final String PROOF = "X-Ezeebit-Gateway";

	private static RedisStore store;
	private static UpstreamStub upstream;
	private static ConfigurableApplicationContext gateway;

	@BeforeAll
	static void start() {
		store = RedisStore.started();
		upstream = UpstreamStub.start();
		gateway = Gateways.start(store.args(), upstream.instances());
	}

	@AfterAll
	static void stop() {
		gateway.close();
		upstream.close();
		store.stop();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ledger", "conversion" })
	void eachPublicSpecIsFetchedFromItsServiceWithTheGatewaysProof(final String service) {
		String secret = gateway.getEnvironment().getRequiredProperty("ezeebit.gateway.shared-secret");
		Map<String, Double> before = Gateways.outcomes(gateway);

		ResponseEntity<String> reply = get(gateway, "/v3/api-docs/%s".formatted(service), PROOF, "forged");

		assertThat(reply.getStatusCode().value()).isEqualTo(200);
		assertThat(upstream.last().path()).isEqualTo("/v3/api-docs/public");
		assertThat(upstream.last().header(PROOF)).containsExactly(secret);
		assertThat(reply.getHeaders().containsHeader(RateLimitFilter.REMAINING)).as("uncharged by design").isFalse();
		assertThat(Gateways.outcomes(gateway)).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/v3/api-docs/internal", "/v3/api-docs/public", "/v3/api-docs/ledger/internal",
		"/v3/api-docs/ledger-internal", "/v3/api-docs.yaml/internal", "/internal/v1/deposits" })
	void theInternalSpecAndAnythingNotAllowListedIsNeverForwarded(final String path) {
		int forwarded = upstream.hits();

		assertThat(get(gateway, path).getStatusCode().value()).as(path).isEqualTo(404);
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

	@ParameterizedTest
	@CsvSource({ "POST", "PUT", "DELETE" })
	void aDocRouteForwardsGetOnly(final String method) {
		int forwarded = upstream.hits();
		ResponseEntity<String> reply = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
				.build().method(HttpMethod.valueOf(method)).uri(url("/v3/api-docs/ledger")).retrieve()
				.toEntity(String.class);

		assertThat(reply.getStatusCode().is2xxSuccessful()).as(method).isFalse();
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

	@Test
	void swaggerUiListsBothPublicSpecsAndNothingElse() {
		JsonNode config = JsonMapper.shared().readTree(get(gateway, "/v3/api-docs/swagger-config").getBody());

		assertThat(config.path("urls").valueStream().map(url -> "%s=%s".formatted(url.path("name").asString(), url.path("url").asString())))
				.containsExactlyInAnyOrder("ledger=/v3/api-docs/ledger", "conversion=/v3/api-docs/conversion");
	}

	@Test
	void swaggerUiIsServedByTheGateway() {
		ResponseEntity<String> entry = get(gateway, "/swagger-ui.html");
		ResponseEntity<String> page = get(gateway, "/swagger-ui/index.html");

		assertThat(entry.getStatusCode().value()).isEqualTo(302);
		assertThat(entry.getHeaders().getLocation()).hasToString("/swagger-ui/index.html");
		assertThat(page.getStatusCode().value()).isEqualTo(200);
		assertThat(page.getBody()).contains("swagger-ui");
		assertThat(get(gateway, "/swagger-ui/swagger-initializer.js").getBody()).contains("SwaggerUIBundle");
	}

	// springdoc's UI needs its own api-docs endpoint for swagger-config; that document must describe nothing.
	@Test
	void theGatewaysOwnDocumentDescribesNothing() {
		ResponseEntity<String> own = get(gateway, "/v3/api-docs");

		assertThat(JsonMapper.shared().readTree(own.getBody()).path("paths").propertyNames()).as(own.getBody()).isEmpty();
	}

	private static URI url(final String path) {
		return URI.create("http://127.0.0.1:%s%s".formatted(gateway.getEnvironment().getRequiredProperty("local.server.port"), path));
	}

}
