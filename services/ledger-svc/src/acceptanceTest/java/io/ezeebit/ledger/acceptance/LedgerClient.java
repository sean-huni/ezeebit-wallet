package io.ezeebit.ledger.acceptance;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

// Real HTTP against the running service, dialled on the loopback address the server is bound to.
public class LedgerClient {

	private final Environment environment;
	private RestClient client;

	LedgerClient(final Environment environment) {
		this.environment = environment;
	}

	public Reply deposit(@Nullable final String idempotencyKey, final String body) {
		RestClient.RequestBodySpec request = client().post().uri("/internal/v1/deposits")
				.contentType(MediaType.APPLICATION_JSON);
		if (idempotencyKey != null) {
			request = request.header("Idempotency-Key", idempotencyKey);
		}
		return reply(request.body(body).retrieve().toEntity(String.class));
	}

	public Reply get(final String path) {
		return reply(client().get().uri(path).retrieve().toEntity(String.class));
	}

	private synchronized RestClient client() {
		if (client == null) {
			String port = environment.getRequiredProperty("local.server.port");
			client = RestClient.builder()
					.baseUrl("http://127.0.0.1:%s".formatted(port))
					.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
					.build();
		}
		return client;
	}

	private static Reply reply(final ResponseEntity<String> entity) {
		return new Reply(entity.getStatusCode().value(), entity.getBody() == null ? "" : entity.getBody());
	}

}
