package io.ezeebit.conversion.outbound.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;

import io.ezeebit.conversion.service.port.DeadLetters;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

// A property name Resilience4j does not know binds nothing and fails nothing: read the served values back.
class LedgerGuardConfigTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
			.withInitializer(app -> app.getEnvironment().getPropertySources().addFirst(served()))
			.withBean(DeadLetters.class, () -> letter -> {
			}).withBean(LedgerGuard.class);

	@Test
	void theServedBudgetsReachEveryInstance() {
		context.run(app -> {
			assertThat(app).hasNotFailed();
			RetryRegistry retries = app.getBean(RetryRegistry.class);
			CircuitBreakerRegistry breakers = app.getBean(CircuitBreakerRegistry.class);
			assertBackoff(retries.retry("ledgerPost").getRetryConfig(), 200, 400);
			assertBackoff(retries.retry("ledgerFind").getRetryConfig(), 500, 1_000);
			assertBackoff(retries.retry("ledgerVoid").getRetryConfig(), 500, 1_000);
			for (Operation op : Operation.values()) {
				CircuitBreakerConfig breaker = breakers.circuitBreaker(op.instance()).getCircuitBreakerConfig();
				assertThat(breaker.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
				assertThat(breaker.getSlidingWindowSize()).isEqualTo(20);
				assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(20);
				assertThat(breaker.getFailureRateThreshold()).isEqualTo(50f);
				assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
				assertThat(breaker.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
				assertThat(breaker.getRecordExceptionPredicate().test(Status.FAILED_PRECONDITION.asRuntimeException()))
						.as("%s records a refusal", op).isFalse();
				assertThat(retries.retry(op.instance()).getRetryConfig().getExceptionPredicate()
						.test(Status.FAILED_PRECONDITION.asRuntimeException())).as("%s retries a refusal", op).isFalse();
			}
		});
	}

	private static void assertBackoff(final RetryConfig retry, final long first, final long second) {
		assertThat(retry.getMaxAttempts()).isEqualTo(3);
		assertThat(retry.getIntervalBiFunction().apply(1, Either.left(new IllegalStateException()))).isEqualTo(first);
		assertThat(retry.getIntervalBiFunction().apply(2, Either.left(new IllegalStateException()))).isEqualTo(second);
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
