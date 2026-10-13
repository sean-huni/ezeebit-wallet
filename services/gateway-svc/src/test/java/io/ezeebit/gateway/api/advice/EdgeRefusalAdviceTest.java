package io.ezeebit.gateway.api.advice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EdgeRefusalAdviceTest {

	// Rounded up, never to zero: a client that waits the advertised time must find a token.
	@ParameterizedTest
	@CsvSource({ "0, 1", "1, 1", "999, 1", "1000, 1", "1001, 2", "49999, 50" })
	void retryAfterIsWholeSecondsRoundedUp(final long millis, final long seconds) {
		assertThat(EdgeRefusalAdvice.seconds(millis)).isEqualTo(seconds);
	}

}
