package io.ezeebit.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

// Found by Schemathesis (DAST, 2026-11-05): a multipart Content-Type without a boundary failed the multipart parse and
// answered 500, on every route. Nothing here accepts multipart, so the header is ignored or refused as 415.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
class MultipartRequestIT {

	private static final String MULTIPART = "multipart/form-data";
	private static final RestClient CLIENT = RestClient.builder()
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	@LocalServerPort
	private int port;

	@Value("${ezeebit.gateway.shared-secret}")
	private String secret;

	@Test
	void aReadIgnoresAMultipartContentType() {
		ResponseEntity<String> reply = CLIENT.get().uri(uri("/v1/merchants/%s/balances".formatted(UUID.randomUUID())))
				.header("X-Ezeebit-Gateway", secret).header("Content-Type", MULTIPART).retrieve().toEntity(String.class);

		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(200);
	}

	@Test
	void aJsonWriteRefusesAMultipartBodyAs415() {
		ResponseEntity<String> reply = CLIENT.post().uri(uri("/internal/v1/deposits"))
				.header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", MULTIPART).body("x")
				.retrieve().toEntity(String.class);

		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(415);
		assertThat(reply.getBody()).contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"");
	}

	private URI uri(final String path) {
		return URI.create("http://127.0.0.1:%d%s".formatted(port, path));
	}

}
