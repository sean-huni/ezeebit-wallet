package io.ezeebit.ledger.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import io.ezeebit.ledger.api.advice.ViolationReader;
import io.ezeebit.ledger.ctl.DepositCtrl;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.req.StatementReq;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Reads the bundle FILE, not a MessageSource: a MessageSource can fall back and hide a missing key.
class MessagesBundleTest {

	private static final Properties BUNDLE = new Properties();

	@BeforeAll
	static void load() throws IOException {
		try (InputStream in = MessagesBundleTest.class.getResourceAsStream("/i18n/messages.properties")) {
			assertThat(in).as("i18n/messages.properties on the classpath").isNotNull();
			BUNDLE.load(in);
		}
	}

	@Test
	void everyErrorCodeHasText() {
		assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::key))
				.allSatisfy(key -> assertThat(BUNDLE.getProperty(key)).as(key).isNotBlank());
	}

	@Test
	void everyValidationKeyUsedOnTheApiHasText() {
		// Constraint annotations do not target RECORD_COMPONENT: they land on the generated fields.
		Stream<Annotation> onRecords = Stream.of(DepositReq.class, StatementReq.class)
				.flatMap(type -> Arrays.stream(type.getDeclaredFields()))
				.flatMap(field -> Arrays.stream(field.getAnnotations()));
		Stream<Annotation> onParameters = Arrays.stream(DepositCtrl.class.getDeclaredMethods())
				.flatMap(method -> Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream));
		var keys = Stream.concat(onRecords, onParameters).map(MessagesBundleTest::messageKey)
				.filter(key -> !key.isEmpty()).toList();

		assertThat(keys).isNotEmpty().allSatisfy(key -> assertThat(BUNDLE.getProperty(key)).as(key).isNotBlank());
	}

	@Test
	void typeMismatchTextExists() {
		assertThat(BUNDLE.getProperty(ViolationReader.TYPE_MISMATCH)).isNotBlank();
	}

	private static String messageKey(final Annotation annotation) {
		try {
			Method message = annotation.annotationType().getMethod("message");
			String template = (String) message.invoke(annotation);
			return template.startsWith("{validation.") ? template.substring(1, template.length() - 1) : "";
		}
		catch (ReflectiveOperationException ex) {
			return "";
		}
	}

}
