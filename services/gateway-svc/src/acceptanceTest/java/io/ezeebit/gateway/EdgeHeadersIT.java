package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.get;
import static io.ezeebit.gateway.Gateways.send;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

// The served route table end to end: public /api/v1 in, service /v1 out, and only the gateway's own proof header.
class EdgeHeadersIT {

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
	@ValueSource(strings = { "balances", "entries", "quotes", "conversions/c-1" })
	void everyRouteIsRewrittenFromApiV1ToV1(final String resource) {
		UUID merchant = UUID.randomUUID();

		assertThat(get(gateway, "/api/v1/merchants/%s/%s".formatted(merchant, resource)).getStatusCode().value()).isEqualTo(200);

		assertThat(upstream.last().path()).isEqualTo("/v1/merchants/%s/%s".formatted(merchant, resource));
	}

	@ParameterizedTest
	@ValueSource(strings = { PROOF, "x-ezeebit-gateway" })
	void aClientSentProofHeaderIsReplacedByTheGatewaysOwn(final String forgedName) {
		String secret = gateway.getEnvironment().getRequiredProperty("ezeebit.gateway.shared-secret");

		get(gateway, "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID()), forgedName, "forged");

		assertThat(secret).isNotBlank().isNotEqualTo("forged");
		assertThat(upstream.last().header(PROOF)).containsExactly(secret);
	}

	// Found by Schemathesis (DAST, 2026-11-05): QUERY and FOO reached the proxy client, which threw, so the edge answered 500;
	// PATCH and DELETE were forwarded. An unserved method now matches no route: 404, as for any unpublished path.
	@ParameterizedTest
	@CsvSource({ "QUERY,balances", "FOO,entries", "QUERY,quotes", "FOO,conversions/c-1", "PATCH,balances", "DELETE,conversions/c-1" })
	void aMethodNoRouteServesIsRefusedAndNeverForwarded(final String method, final String resource) {
		int forwarded = upstream.hits();

		ResponseEntity<String> response = send(gateway, method, "/api/v1/merchants/%s/%s".formatted(UUID.randomUUID(), resource));

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

	// RFC 9110 section 9.1: a server supports HEAD wherever it supports GET. The method fix first left it out (review, 2026-11-05).
	@ParameterizedTest
	@ValueSource(strings = { "balances", "entries", "conversions/c-1" })
	void headIsForwardedWhereverGetIs(final String resource) {
		int forwarded = upstream.hits();

		ResponseEntity<String> response = send(gateway, "HEAD", "/api/v1/merchants/%s/%s".formatted(UUID.randomUUID(), resource));

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(upstream.hits()).isEqualTo(forwarded + 1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "/internal/v1/deposits", "/api/v1/internal/v1/deposits", "/v1/merchants/%s/balances" })
	void nothingOutsideThePublishedRoutesIsForwarded(final String template) {
		int forwarded = upstream.hits();

		assertThat(get(gateway, template.formatted(UUID.randomUUID())).getStatusCode().value()).isEqualTo(404);
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

}
