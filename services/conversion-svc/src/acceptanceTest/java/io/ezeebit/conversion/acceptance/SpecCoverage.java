package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

import io.cucumber.java.AfterAll;
import io.ezeebit.conversion.api.doc.OperationErrors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Every answer the scenarios provoke must be one the committed spec documents: its status, and for an error its code.
// This is the half of the contract a spec generator cannot prove, because it reads annotations and not behaviour.
public final class SpecCoverage {

	private static final List<String> SPECS = List.of("conversion-svc.json");
	private static final Queue<Seen> SEEN = new ConcurrentLinkedQueue<>();

	private record Seen(String method, String path, int status, String code) {
	}

	private record Operation(Pattern path, String method, JsonNode node) {
	}

	private SpecCoverage() {
	}

	static void record(final String method, final String path, final Reply reply) {
		String code = reply.status() >= 400 ? JsonMapper.shared().readTree(reply.body()).path("code").asString() : "";
		SEEN.add(new Seen(method.toUpperCase(Locale.ROOT), path.split("\\?")[0], reply.status(), code));
	}

	@AfterAll
	public static void everyObservedAnswerIsDocumented() {
		List<Operation> operations = operations();
		Set<String> undocumented = new TreeSet<>();
		for (Seen seen : SEEN) {
			Optional<Operation> match = operations.stream()
					.filter(operation -> operation.method().equals(seen.method()) && operation.path().matcher(seen.path()).matches())
					.findFirst();
			if (match.isEmpty()) {
				if (OperationErrors.ROUTING.stream().noneMatch(code -> code.name().equals(seen.code()))) {
					undocumented.add("%s %s -> %d %s: no operation, and not a routing answer".formatted(seen.method(), seen.path(),
							seen.status(), seen.code()));
				}
				continue;
			}
			JsonNode response = match.get().node().path("responses").path(Integer.toString(seen.status()));
			boolean documented = !response.isMissingNode() && (seen.code().isEmpty() || response.findValues("enum").stream()
					.anyMatch(values -> values.valueStream().anyMatch(value -> value.asString().equals(seen.code()))));
			if (!documented) {
				undocumented.add("%s %s -> %d %s".formatted(seen.method(), match.get().node().path("operationId").asString(),
						seen.status(), seen.code()));
			}
		}
		assertThat(SEEN).as("the scenarios recorded no answers, so this check proved nothing").isNotEmpty();
		assertThat(undocumented).as("answers the services gave that docs/openapi does not document").isEmpty();
	}

	private static List<Operation> operations() {
		Path dir = Path.of(System.getProperty("openapi.snapshots"));
		List<Operation> operations = new ArrayList<>();
		for (String file : SPECS) {
			JsonNode spec = JsonMapper.shared().readTree(dir.resolve(file).toFile());
			for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
				Pattern pattern = Pattern.compile("^%s$".formatted(path.getKey().replaceAll("\\{[^/]+}", "[^/]+")));
				path.getValue().properties().forEach(method -> operations.add(
						new Operation(pattern, method.getKey().toUpperCase(Locale.ROOT), method.getValue())));
			}
		}
		return operations;
	}

}
