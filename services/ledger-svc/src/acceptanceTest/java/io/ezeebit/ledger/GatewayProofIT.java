package io.ezeebit.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

// Public merchant routes answer only requests carrying the gateway's proof header; /internal/** is not covered.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
class GatewayProofIT {

	private static final String PROOF = "X-Ezeebit-Gateway";
	private static final String MERCHANT = UUID.randomUUID().toString();
	private static final RestClient CLIENT = RestClient.builder()
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	@LocalServerPort
	private int port;

	@Value("${ezeebit.gateway.shared-secret}")
	private String secret;

	@Test
	void aPublicRouteWithoutTheHeaderIs403() {
		assertRefused(get("/v1/merchants/%s/balances".formatted(MERCHANT), null));
	}

	@Test
	void aPublicRouteWithTheWrongHeaderIs403() {
		assertRefused(get("/v1/merchants/%s/balances".formatted(MERCHANT), "forged"));
		assertRefused(get("/v1/merchants/%s/balances".formatted(MERCHANT), secret.substring(0, secret.length() - 1)));
		assertRefused(get("/v1/merchants/%s/balances".formatted(MERCHANT), "%sx".formatted(secret)));
		assertRefused(get("/v1/merchants/%s/balances".formatted(MERCHANT), ""));
	}

	@Test
	void aPublicRouteWithTheGatewaysHeaderPasses() {
		// a merchant with no entries has an empty balance list
		assertThat(get("/v1/merchants/%s/balances".formatted(MERCHANT), secret).getStatusCode().value()).isEqualTo(200);
	}

	// Without the header the deposit reaches validation: 400 for the empty body, never the guard's 403.
	@Test
	void internalRoutesAreNotCovered() {
		ResponseEntity<String> reply = CLIENT.post().uri(URI.create("http://127.0.0.1:%d/internal/v1/deposits".formatted(port)))
				.contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString()).body("{}")
				.retrieve().toEntity(String.class);

		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(400);
		assertThat(reply.getBody()).contains("\"code\":\"VALIDATION_FAILED\"");
	}

	// The routing matcher ignores ;params and resolves dot segments, so the guard must read the same normalised path.
	@Test
	void aPathDisguisedFromTheGuardIsStillGuarded() {
		assertRefused(get("/v1/merchants;x=1/%s/balances".formatted(MERCHANT), null));
		assertRefused(get("/v1;x=1/merchants/%s/balances".formatted(MERCHANT), null));
		assertRefused(get("/internal/../v1/merchants/%s/balances".formatted(MERCHANT), null));
	}

	@Test
	void everyApiVersionOfAMerchantRouteIsGuarded() {
		assertRefused(get("/v2/merchants/%s/balances".formatted(MERCHANT), null));
	}

	@Test
	void theOldApiPrefixIsNotServed() {
		ResponseEntity<String> withProof = get("/api/v1/merchants/%s/balances".formatted(MERCHANT), secret);
		ResponseEntity<String> without = get("/api/v1/merchants/%s/balances".formatted(MERCHANT), null);

		assertThat(withProof.getStatusCode().value()).as(withProof.getBody()).isEqualTo(404);
		assertThat(withProof.getBody()).contains("\"code\":\"NOT_FOUND\"");
		assertThat(without.getStatusCode().value()).isEqualTo(404);
	}

	private static void assertRefused(final ResponseEntity<String> reply) {
		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(403);
		assertThat(reply.getBody()).contains("\"code\":\"GATEWAY_REQUIRED\"");
	}

	private ResponseEntity<String> get(final String path, @Nullable final String proof) {
		RestClient.RequestHeadersSpec<?> request = CLIENT.get().uri(URI.create("http://127.0.0.1:%d%s".formatted(port, path)));
		if (proof != null) {
			request = request.header(PROOF, proof);
		}
		return request.retrieve().toEntity(String.class);
	}

}
