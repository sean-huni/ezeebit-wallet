package io.ezeebit.ledger;

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

import io.ezeebit.ledger.api.advice.ErrorStatus;
import io.ezeebit.ledger.api.doc.OperationErrors;
import io.ezeebit.ledger.exception.ErrorCode;
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
	private static final String CONTROLLERS = "io.ezeebit.ledger.ctl";
	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
			.enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
	private static final RestClient CLIENT = RestClient.builder()
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	// Success codes are what each handler promises; error codes follow from OperationErrors through ErrorStatus.
	private static final Map<String, Set<Integer>> EXPECTED_STATUSES = Map.of(
			"GET /v1/merchants/{merchantId}/balances", Set.of(200, 400, 403, 500),
			"GET /v1/merchants/{merchantId}/entries", Set.of(200, 400, 403, 500),
			"POST /internal/v1/deposits", Set.of(201, 400, 415, 422, 500));

	// Answered only by the conversion gRPC services since their REST endpoints went; GrpcErrorsTest maps each one.
	private static final Set<ErrorCode> GRPC_ONLY = EnumSet.of(ErrorCode.SAME_CURRENCY, ErrorCode.POSTING_DEADLINE_PASSED,
			ErrorCode.CONVERSION_VOIDED, ErrorCode.CONVERSION_ALREADY_POSTED, ErrorCode.INSUFFICIENT_FUNDS,
			ErrorCode.POSTING_NOT_FOUND);

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
		assertThat(operations(spec("public"))).isEqualTo(controllerOperations("/v1/"));
		assertThat(operations(spec("internal"))).isEqualTo(controllerOperations("/internal/"));
	}

	@Test
	void requiredHeadersAreTheOnesTheControllersRequire() {
		Map<String, Set<String>> published = new TreeMap<>();
		for (String group : List.of("public", "internal")) {
			eachOperation(spec(group), (key, operation) -> published.put(key, requiredHeaders(operation)));
		}
		Map<String, Set<String>> declared = new TreeMap<>();
		controllerHandlers().forEach((key, handler) -> declared.put(key, declaredHeaders(handler)));

		assertThat(published).isEqualTo(declared);
		assertThat(published.get("POST /internal/v1/deposits")).containsExactly("Idempotency-Key");
	}

	@Test
	void everyOperationAnswersExactlyItsExpectedStatuses() {
		Map<String, Set<Integer>> published = new TreeMap<>();
		for (String group : List.of("public", "internal")) {
			eachOperation(spec(group), (key, operation) -> published.put(key, statuses(operation)));
		}
		assertThat(published).isEqualTo(new TreeMap<>(EXPECTED_STATUSES));
	}

	@Test
	void everyErrorResponseIsAProblemListingTheCodesThatMapToItsStatus() {
		for (String group : List.of("public", "internal")) {
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
		published.addAll(GRPC_ONLY);
		for (String group : List.of("public", "internal")) {
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
		assertThat(spec.toString()).contains("53.982730", "500.00");
		assertThat(spec("internal").toString()).contains("1500.00");
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

	// Never routed by the gateway; like /internal/** itself it is guarded by the loopback bind (LoopbackOnlyIT).
	@Test
	void theInternalSpecAnswersOnLoopbackWithoutTheProof() {
		assertThat(get("/v3/api-docs/internal", null).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void anUnknownGroupIsNotFound() {
		ResponseEntity<String> reply = get("/v3/api-docs/nope", secret);

		assertThat(reply.getStatusCode().value()).as(reply.getBody()).isEqualTo(404);
		assertThat(reply.getBody()).contains("\"code\":\"NOT_FOUND\"");
	}

	@Test
	void theCommittedSnapshotsMatchThePublishedSpecs() throws IOException {
		for (String group : List.of("public", "internal")) {
			Path file = snapshots.resolve("public".equals(group) ? "ledger-svc.json" : "ledger-svc-internal.json");
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
