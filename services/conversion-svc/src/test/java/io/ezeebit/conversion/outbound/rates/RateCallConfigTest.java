package io.ezeebit.conversion.outbound.rates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

// A property name Resilience4j does not know binds nothing and fails nothing: read the served values back.
class RateCallConfigTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class))
			.withInitializer(app -> app.getEnvironment().getPropertySources().addFirst(served()));

	@Test
	void theServedBudgetReachesTheRatesInstance() {
		context.run(app -> {
			assertThat(app).hasNotFailed();
			RetryConfig retry = app.getBean(RetryRegistry.class).retry("rates").getRetryConfig();
			assertThat(retry.getMaxAttempts()).isEqualTo(2);
			assertThat(retry.getIntervalBiFunction().apply(1, Either.left(new IllegalStateException()))).isEqualTo(200L);
			assertThat(retry.getExceptionPredicate()
					.test(new ResourceAccessException("read timed out", new SocketTimeoutException()))).as("timeout").isTrue();
			assertThat(retry.getExceptionPredicate()
					.test(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null)))
					.as("5xx").isFalse();
			assertThat(retry.getExceptionPredicate()
					.test(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null)))
					.as("4xx").isFalse();
			assertThat(retry.getExceptionPredicate().test(new RestClientException("unreadable body")))
					.as("unreadable").isFalse();
		});
	}

	private static PropertySource<?> served() {
		String file = Arrays.stream(System.getProperty("spring.config.import").split(","))
				.filter(path -> path.endsWith("conversion-svc.yml")).findFirst().orElseThrow().substring("file:".length());
		try {
			return new YamlPropertySourceLoader().load("served", new FileSystemResource(file)).getFirst();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
