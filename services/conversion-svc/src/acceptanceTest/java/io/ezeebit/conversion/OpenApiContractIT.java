package io.ezeebit.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import io.ezeebit.conversion.api.advice.ErrorStatus;
import io.ezeebit.conversion.api.doc.OperationErrors;
import io.ezeebit.conversion.exception.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

// The published spec against the controllers that serve it, and against the snapshot committed in docs/openapi.
// -Dopenapi.snapshots.write=true (./gradlew generateOpenApiSnapshots) rewrites the snapshots instead of comparing.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
class OpenApiContractIT {

	private static final String PROOF = "X-Ezeebit-Gateway";
	private static final String CONTROLLERS = "io.ezeebit.conversion.ctl";
	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
			.enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
	private static final RestClient CLIENT = RestClient.builder()
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	// Success codes are what each handler promises; error codes follow from OperationErrors through ErrorStatus.
	private static final Map<String, Set<Integer>> EXPECTED_STATUSES = Map.of(
			"POST /v1/merchants/{merchantId}/quotes", Set.of(201, 400, 403, 415, 500, 502, 503),
			"POST /v1/merchants/{merchantId}/conversions", Set.of(201, 202, 400, 403, 404, 409, 415, 422, 500),
			"GET /v1/merchants/{merchantId}/conversions/{conversionId}", Set.of(200, 400, 403, 404, 500));
	private static final List<String> GROUPS = List.of("public");

	@LocalServerPort
	private int port;

	@Value("${ezeebit.gateway.shared-secret}")
	private String secret;

	@Value("${openapi.snapshots}")
	private Path snapshots;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	@Test
	void eachGroupPublishesExactlyTheOperationsItsControllersMap() {
		assertThat(operations(spec("public"))).isEqualTo(controllerOperations("/"));
	}

	@Test
	void requiredHeadersAreTheOnesTheControllersRequire() {
		Map<String, Set<String>> published = new TreeMap<>();
		for (String group : GROUPS) {
			eachOperation(spec(group), (key, operation) -> published.put(key, requiredHeaders(operation)));
		}
		Map<String, Set<String>> declared = new TreeMap<>();
		controllerHandlers().forEach((key, handler) -> declared.put(key, declaredHeaders(handler)));

		assertThat(published).isEqualTo(declared);
		assertThat(published.get("POST /v1/merchants/{merchantId}/conversions")).containsExactly("Idempotency-Key");
	}

	@Test
	void everyOperationAnswersExactlyItsExpectedStatuses() {
		Map<String, Set<Integer>> published = new TreeMap<>();
		for (String group : GROUPS) {
			eachOperation(spec(group), (key, operation) -> published.put(key, statuses(operation)));
		}
		assertThat(published).isEqualTo(new TreeMap<>(EXPECTED_STATUSES));
	}

	@Test
	void everyErrorResponseIsAProblemListingTheCodesThatMapToItsStatus() {
		for (String group : GROUPS) {
			eachOperation(spec(group), (key, operation) -> {
				Map<Integer, Set<String>> expected = new TreeMap<>();
				OperationErrors.of(operation.path("operationId").asString()).forEach(code -> expected
						.computeIfAbsent(ErrorStatus.of(code).value(), status -> new TreeSet<>()).add(code.name()));
				Map<Integer, Set<String>> published = new TreeMap<>();
				operation.path("responses").properties().stream().filter(entry -> Integer.parseInt(entry.getKey()) >= 400)
						.forEach(entry -> published.put(Integer.parseInt(entry.getKey()), codes(entry.getValue())));

				assertThat(published).as(key).isEqualTo(expected);
			});
		}
	}

	@Test
	void everyErrorCodeIsPublishedByAnOperationOrAsARoutingAnswer() {
		Set<ErrorCode> published = EnumSet.copyOf(OperationErrors.ROUTING);
		for (String group : GROUPS) {
			eachOperation(spec(group), (key, operation) -> codes(operation).forEach(code -> published.add(ErrorCode.valueOf(code))));
			assertThat(spec(group).at("/components/schemas/ErrorCode/enum").valueStream().map(JsonNode::asString))
					.containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ErrorCode.class).stream().map(Enum::name).toList());
		}
		assertThat(published).isEqualTo(EnumSet.allOf(ErrorCode.class));
	}

	@Test
	void thePublicSpecPointsAtTheGatewayAndCarriesTheDemoValues() {
		JsonNode spec = spec("public");

		assertThat(spec.at("/servers/0/url").asString()).isEqualTo("http://localhost:8080/api");
		assertThat(spec.at("/info/description").asString()).contains("429 RATE_LIMITED", "503 RATE_LIMITER_UNAVAILABLE");
		assertThat(spec.toString()).contains("1000.00", "53.982730", "0.05398273", "0.054254");
	}

	@Test
	void thePublicSpecIsServedOnlyToTheGateway() {
		ResponseEntity<String> without = get("/v3/api-docs/public", null);
		ResponseEntity<String> forged = get("/v3/api-docs/public", "forged");

		assertThat(without.getStatusCode().value()).isEqualTo(403);
		assertThat(without.getBody()).contains("\"code\":\"GATEWAY_REQUIRED\"");
		assertThat(forged.getStatusCode().value()).isEqualTo(403);
		assertThat(get("/v3/api-docs.yaml/public", null).getStatusCode().value()).isEqualTo(403);
		assertThat(get("/v3/api-docs/public", secret).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void thereIsNoInternalSpec() {
		assertThat(get("/v3/api-docs/internal", secret).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void anUnknownGroupIsNotFound() {
		ResponseEntity<String> reply = get("/v3/api-docs/nope", secret);

		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(404);
		assertThat(reply.getBody()).contains("\"code\":\"NOT_FOUND\"");
	}

	@Test
	void theCommittedSnapshotsMatchThePublishedSpecs() throws IOException {
		for (String group : GROUPS) {
			Path file = snapshots.resolve("conversion-svc.json");
			String published = "%s\n".formatted(JSON.writeValueAsString(spec(group)));
			if (Boolean.getBoolean("openapi.snapshots.write")) {
				Files.createDirectories(snapshots);
				Files.writeString(file, published, StandardCharsets.UTF_8);
			}
			assertThat(file).as("run ./gradlew generateOpenApiSnapshots and commit %s", file).exists();
			assertThat(Files.readString(file, StandardCharsets.UTF_8))
					.as("%s drifted from the controllers: run ./gradlew generateOpenApiSnapshots and commit it", file).isEqualTo(published);
		}
	}

	private JsonNode spec(final String group) {
		ResponseEntity<String> reply = get("/v3/api-docs/%s".formatted(group), secret);
		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(200);
		return JSON.readTree(reply.getBody());
	}

	private ResponseEntity<String> get(final String path, @Nullable final String proof) {
		RestClient.RequestHeadersSpec<?> request = CLIENT.get().uri(URI.create("http://127.0.0.1:%d%s".formatted(port, path)));
		if (proof != null) {
			request = request.header(PROOF, proof);
		}
		return request.retrieve().toEntity(String.class);
	}

	private static Set<String> operations(final JsonNode spec) {
		Set<String> keys = new TreeSet<>();
		eachOperation(spec, (key, operation) -> keys.add(key));
		return keys;
	}

	private static void eachOperation(final JsonNode spec, final BiConsumer<String, JsonNode> action) {
		spec.path("paths").properties().forEach(path -> path.getValue().properties()
				.forEach(method -> action.accept("%s %s".formatted(method.getKey().toUpperCase(Locale.ROOT), path.getKey()),
						method.getValue())));
	}

	private static Set<String> requiredHeaders(final JsonNode operation) {
		return operation.path("parameters").valueStream()
				.filter(parameter -> "header".equals(parameter.path("in").asString()) && parameter.path("required").asBoolean())
				.map(parameter -> parameter.path("name").asString()).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<Integer> statuses(final JsonNode operation) {
		return operation.path("responses").propertyNames().stream().map(Integer::parseInt)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> codes(final JsonNode responseOrOperation) {
		Set<String> codes = new TreeSet<>();
		responseOrOperation.findValues("enum").forEach(values -> values.valueStream().map(JsonNode::asString).forEach(codes::add));
		return codes;
	}

	private Set<String> controllerOperations(final String prefix) {
		return controllerHandlers().keySet().stream().filter(key -> key.split(" ")[1].startsWith(prefix))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private Map<String, HandlerMethod> controllerHandlers() {
		Map<String, HandlerMethod> handlers = new TreeMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
			if (!entry.getValue().getBeanType().getPackageName().equals(CONTROLLERS)) {
				continue;
			}
			for (String pattern : entry.getKey().getPatternValues()) {
				String path = pattern.replace("{version:v\\d+}", "v1").replace("{version}", "v1");
				entry.getKey().getMethodsCondition().getMethods()
						.forEach(method -> handlers.put("%s %s".formatted(method.name(), path), entry.getValue()));
			}
		}
		return handlers;
	}

	private static Set<String> declaredHeaders(final HandlerMethod handler) {
		Set<String> headers = new TreeSet<>();
		for (MethodParameter parameter : handler.getMethodParameters()) {
			RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
			if (header != null && header.required()) {
				headers.add(header.value());
			}
		}
		return headers;
	}

}
