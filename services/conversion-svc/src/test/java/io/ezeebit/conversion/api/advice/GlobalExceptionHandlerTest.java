package io.ezeebit.conversion.api.advice;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.transaction.TransactionSystemException;

class GlobalExceptionHandlerTest {

	private final TestObservationRegistry observations = TestObservationRegistry.create();
	private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ProblemFactory(codesAsText()), observations);

	private static StaticMessageSource codesAsText() {
		StaticMessageSource messages = new StaticMessageSource();
		messages.setUseCodeAsDefaultMessage(true);
		return messages;
	}

	// An answered 500 never propagates out of the request, so the request's own span would show no exception.
	@Test
	void anUnhandledFailureIsRecordedOnTheRequestSpan() {
		TransactionSystemException failure = new TransactionSystemException("could not roll back");
		Observation request = Observation.start("http.server.requests", observations);

		try (Observation.Scope scope = request.openScope()) {
			assertThat(handler.unexpected(failure).getStatus()).isEqualTo(500);
		}
		request.stop();

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat().hasError(failure);
	}

	@Test
	void withNoRequestSpanOpenItStillAnswers() {
		assertThat(handler.unexpected(new IllegalStateException("no span")).getStatus()).isEqualTo(500);
	}

}
