package io.ezeebit.conversion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

// gradle.properties holds the pin; compose cannot read it, so this is what keeps compose and Testcontainers on one image.
class KafkaPinTest {

	@Test
	void composeRunsTheImageTheTestsRun() throws IOException {
		String pin = System.getProperty("kafka.image");
		assertThat(pin).as("kafka.image from gradle.properties").matches("apache/kafka:[0-9.]+@sha256:[0-9a-f]{64}");

		assertThat(Files.readString(Path.of(System.getProperty("compose.file")))).contains("image: '%s'".formatted(pin));
	}

}
