package io.ezeebit.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

// gradle.properties holds the pin; compose cannot read it, so this is what keeps compose and Testcontainers on one image.
class RedisPinTest {

	@Test
	void composeRunsTheImageTheTestsRun() throws IOException {
		String pin = System.getProperty("redis.image");
		assertThat(pin).as("redis.image from gradle.properties").matches("redis:[0-9.]+-alpine[0-9.]*@sha256:[0-9a-f]{64}");

		assertThat(Files.readString(Path.of(System.getProperty("compose.file")))).contains("image: '%s'".formatted(pin));
	}

}
