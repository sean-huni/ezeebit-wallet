package io.ezeebit.ledger.api.doc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

// The RFC 9457 body every error shares, and the full code list; a response narrows `code` to the codes it can carry.
final class ProblemSchemas {

	private static final String REF = "#/components/schemas/%s";

	private ProblemSchemas() {
	}

	static void register(final OpenAPI openApi, final List<String> allCodes) {
		if (openApi.getComponents() == null) {
			openApi.setComponents(new Components());
		}
		Components components = openApi.getComponents();
		components.addSchemas("ErrorCode", typed("string").description("Stable machine-readable error code.")
				._enum(List.copyOf(allCodes)));
		components.addSchemas("Violation", typed("object").properties(Map.of(
				"field", typed("string"), "code", typed("string"),
				"message", typed("string"))));
		components.addSchemas("Problem", typed("object")
				.description("RFC 9457 problem detail. `code` is stable; `detail` is localised by Accept-Language.")
				.properties(Map.of(
						"type", typed("string").format("uri"),
						"title", typed("string"),
						"status", typed("integer"),
						"detail", typed("string"),
						"instance", typed("string"),
						"code", new Schema<>().$ref(REF.formatted("ErrorCode")),
						"field", typed("string").description("The request field a business rule refused, when one did."),
						"errors", typed("array").items(new Schema<>().$ref(REF.formatted("Violation")))
								.description("One entry per invalid field, on VALIDATION_FAILED.")))
				.required(List.of("title", "status", "code")));
	}

	static Schema<Object> restrictedTo(final List<String> codes) {
		Schema<Object> narrowed = typed("object")
				.properties(Map.of("code", typed("string")._enum(List.copyOf(codes))));
		return new Schema<>().allOf(List.of(new Schema<>().$ref(REF.formatted("Problem")), narrowed));
	}

	static Schema<Object> typed(final String type) {
		return new Schema<>().type(type).types(Set.of(type));
	}

}
