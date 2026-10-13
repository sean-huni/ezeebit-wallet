package io.ezeebit.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.Test;

// Reads the bundle FILE, not a MessageSource: a MessageSource can fall back and hide a missing key.
class MessagesBundleTest {

	@Test
	void everyErrorCodeHasText() throws IOException {
		Properties bundle = new Properties();
		try (InputStream in = MessagesBundleTest.class.getResourceAsStream("/i18n/messages.properties")) {
			assertThat(in).as("i18n/messages.properties on the classpath").isNotNull();
			bundle.load(in);
		}

		assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::key))
				.allSatisfy(key -> assertThat(bundle.getProperty(key)).as(key).isNotBlank());
	}

}
