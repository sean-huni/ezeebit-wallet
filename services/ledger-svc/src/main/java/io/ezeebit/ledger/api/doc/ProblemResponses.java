package io.ezeebit.ledger.api.doc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.ezeebit.ledger.api.advice.ErrorStatus;
import io.ezeebit.ledger.exception.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

// Every error response is derived, never hand-written: OperationErrors names the codes, ErrorStatus picks each status.
public final class ProblemResponses implements OpenApiCustomizer {

	private static final String PROBLEM_JSON = "application/problem+json";

	private final MessageSource messages;

	public ProblemResponses(final MessageSource messages) {
		this.messages = messages;
	}

	@Override
	public void customise(final OpenAPI openApi) {
		ProblemSchemas.register(openApi, Arrays.stream(ErrorCode.values()).map(Enum::name).sorted().toList());
		openApi.getPaths().values().forEach(path -> path.readOperations().forEach(this::describe));
	}

	private void describe(final Operation operation) {
		Map<HttpStatus, Set<ErrorCode>> byStatus = new TreeMap<>();
		OperationErrors.of(operation.getOperationId())
				.forEach(code -> byStatus.computeIfAbsent(ErrorStatus.of(code), status -> EnumSet.noneOf(ErrorCode.class)).add(code));
		byStatus.forEach((status, codes) -> operation.getResponses()
				.addApiResponse(Integer.toString(status.value()), response(status, codes)));
	}

	private ApiResponse response(final HttpStatus status, final Set<ErrorCode> codes) {
		List<String> names = codes.stream().map(Enum::name).toList();
		MediaType media = new MediaType().schema(ProblemSchemas.restrictedTo(names));
		codes.forEach(code -> media.addExamples(code.name(), new Example().value(example(status, code))));
		return new ApiResponse().description("%s: %s".formatted(status.getReasonPhrase(), String.join(", ", names)))
				.content(new Content().addMediaType(PROBLEM_JSON, media));
	}

	private Map<String, Object> example(final HttpStatus status, final ErrorCode code) {
		return Map.of("title", status.getReasonPhrase(), "status", status.value(), "code", code.name(),
				"detail", messages.getMessage(code.key(), null, Locale.ENGLISH));
	}

}
