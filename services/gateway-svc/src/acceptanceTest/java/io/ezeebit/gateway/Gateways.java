package io.ezeebit.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.ezeebit.gateway.common.metrics.RateLimitMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

// Gateway processes a test can start several of, each on its own port: the claim under test is two edges on one store.
final class Gateways {

	private static final RestClient CLIENT = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	private Gateways() {
	}

	@SafeVarargs
	static ConfigurableApplicationContext start(final Collection<String>... args) {
		List<String> all = new ArrayList<>(List.of("--server.port=0"));
		for (Collection<String> group : args) {
			all.addAll(group);
		}
		return new SpringApplicationBuilder(GatewayApplication.class).run(all.toArray(String[]::new));
	}

	// The raw path is sent as written, so a test can send a path a URI builder would normalise.
	static ResponseEntity<String> get(final ConfigurableApplicationContext gateway, final String rawPath, final String... headers) {
		RestClient.RequestHeadersSpec<?> request = CLIENT.get().uri(URI.create("http://127.0.0.1:%s%s"
				.formatted(gateway.getEnvironment().getRequiredProperty("local.server.port"), rawPath)));
		for (int i = 0; i < headers.length; i += 2) {
			request = request.header(headers[i], headers[i + 1]);
		}
		return request.retrieve().toEntity(String.class);
	}

	static ResponseEntity<String> post(final ConfigurableApplicationContext gateway, final String rawPath, final String body,
			final String... headers) {
		RestClient.RequestBodySpec request = CLIENT.post().uri(URI.create("http://127.0.0.1:%s%s"
				.formatted(gateway.getEnvironment().getRequiredProperty("local.server.port"), rawPath)))
				.contentType(MediaType.APPLICATION_JSON);
		for (int i = 0; i < headers.length; i += 2) {
			request = request.header(headers[i], headers[i + 1]);
		}
		return request.body(body).retrieve().toEntity(String.class);
	}

	static Map<String, Double> outcomes(final ConfigurableApplicationContext gateway) {
		return gateway.getBean(MeterRegistry.class).find(RateLimitMetrics.NAME).counters().stream()
				.collect(Collectors.toMap(counter -> counter.getId().getTag("outcome"), Counter::count));
	}

	static String header(final ResponseEntity<String> response, final String name) {
		return response.getHeaders().getFirst(name);
	}

	static String code(final ResponseEntity<String> response) {
		return JsonMapper.shared().readTree(response.getBody()).path("code").asString();
	}

}
