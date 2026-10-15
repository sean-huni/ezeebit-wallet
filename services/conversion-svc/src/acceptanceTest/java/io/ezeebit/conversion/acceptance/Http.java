package io.ezeebit.conversion.acceptance;

import java.net.URI;
import java.net.http.HttpClient;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// Real HTTP, to conversion-svc on its random port and straight to the ledger process. The JDK client never
// retries: Apache HttpClient, also on this classpath, silently retries a 503 once and would hide the timing.
public class Http {

	private final Environment environment;
	private final RestClient client = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	Http(final Environment environment) {
		this.environment = environment;
	}

	public Reply conversion(final String method, final String path, @Nullable final String key, @Nullable final String body) {
		return conversion(method, path, key, body, null);
	}

	// traceparent: the W3C trace context a gateway would forward, so the call joins that caller's trace.
	public Reply conversion(final String method, final String path, @Nullable final String key, @Nullable final String body,
			@Nullable final String traceparent) {
		Reply reply = call(method, "http://127.0.0.1:%s%s".formatted(environment.getRequiredProperty("local.server.port"), path),
				key, body, traceparent);
		SpecCoverage.record(method, path, reply);
		return reply;
	}

	public Reply ledger(final String method, final String path, @Nullable final String key, @Nullable final String body) {
		return call(method, Externals.get().ledgerUrl() + path, key, body, null);
	}

	private Reply call(final String method, final String url, @Nullable final String key, @Nullable final String body,
			@Nullable final String traceparent) {
		long start = System.nanoTime();
		RestClient.RequestBodySpec request = client.method(org.springframework.http.HttpMethod.valueOf(method))
				.uri(URI.create(url));
		// Every call arrives as if through the gateway; GatewayProofIT covers the calls that do not.
		request = request.header("X-Ezeebit-Gateway", environment.getRequiredProperty("ezeebit.gateway.shared-secret"));
		if (key != null) {
			request = request.header("Idempotency-Key", key);
		}
		if (traceparent != null) {
			request = request.header("traceparent", traceparent);
		}
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		ResponseEntity<String> entity = request.retrieve().toEntity(String.class);
		return new Reply(entity.getStatusCode().value(), entity.getBody() == null ? "" : entity.getBody(),
				(System.nanoTime() - start) / 1_000_000);
	}

}
