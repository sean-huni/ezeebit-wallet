package io.ezeebit.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

// The services' public specs describe answers the gateway gives on their behalf; a code added here must reach them.
class EdgeAnswersDocumentedTest {

	@ParameterizedTest
	@ValueSource(strings = { "ledger-svc.json", "conversion-svc.json" })
	void everyEdgeCodeIsNamedWithItsStatusInEachPublicSpec(final String spec) {
		String description = JsonMapper.shared().readTree(Path.of(System.getProperty("openapi.snapshots"), spec).toFile())
				.at("/info/description").asString();

		for (ErrorCode code : ErrorCode.values()) {
			assertThat(description).as(spec).contains("%d %s".formatted(code.status(), code.name()));
		}
	}

}
