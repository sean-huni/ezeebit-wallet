package io.ezeebit.gateway.api.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;

class UnsentRetryFilterFunctionsTest {

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void fewerThanOneAttemptIsRefusedAtRouteBuild(final int attempts) {
		assertThatIllegalArgumentException().isThrownBy(() -> UnsentRetryFilterFunctions.retryUnsent(attempts))
				.withMessageContaining("at least 1").withMessageContaining(Integer.toString(attempts));
	}

	@Test
	void aFailedConnectWasNeverSent() {
		assertThat(UnsentRetryFilterFunctions.neverSent(wrapped(new HttpHostConnectException("refused")))).isTrue();
		assertThat(UnsentRetryFilterFunctions.neverSent(wrapped(new ConnectException("refused")))).isTrue();
		assertThat(UnsentRetryFilterFunctions.neverSent(wrapped(new ConnectTimeoutException("connect timed out")))).isTrue();
	}

	@Test
	void aReadTimeoutOrOtherIoFailureMayHaveBeenApplied() {
		assertThat(UnsentRetryFilterFunctions.neverSent(wrapped(new SocketTimeoutException("Read timed out")))).isFalse();
		assertThat(UnsentRetryFilterFunctions.neverSent(wrapped(new IOException("Connection reset")))).isFalse();
	}

	private static ResourceAccessException wrapped(final IOException cause) {
		return new ResourceAccessException("I/O error", cause);
	}

}
