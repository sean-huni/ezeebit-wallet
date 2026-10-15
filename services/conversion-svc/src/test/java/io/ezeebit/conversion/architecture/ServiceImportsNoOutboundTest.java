package io.ezeebit.conversion.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ServiceImportsNoOutboundTest {

	@Test
	void theServiceLayerDependsOnPortsNotAdapters() throws IOException {
		Path service = Path.of("src/main/java/io/ezeebit/conversion/service");
		assertThat(service).isDirectory();
		List<String> offenders;
		try (Stream<Path> files = Files.walk(service)) {
			offenders = files.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> read(p).contains("import io.ezeebit.conversion.outbound."))
					.map(Path::toString).toList();
		}
		assertThat(offenders).as("service/ classes importing outbound/").isEmpty();
	}

	private static String read(final Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
