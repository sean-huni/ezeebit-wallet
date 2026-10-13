package io.ezeebit.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.exception.EdgeRefusalException;
import io.ezeebit.gateway.exception.ErrorCode;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.Test;

class MerchantBucketsTest {

	private static final RateLimitProperties LIMITS = new RateLimitProperties(10, 1, Duration.ofSeconds(1),
			new RateLimitProperties.Store("localhost", 6379, "p", Duration.ofMillis(250)));

	private final AtomicLong clock = new AtomicLong(1_000_000_000L);
	private final AtomicInteger connects = new AtomicInteger();
	private final MerchantBuckets buckets = new MerchantBuckets(() -> {
		connects.incrementAndGet();
		throw new RedisConnectionException("refused");
	}, LIMITS, clock::get);

	@Test
	void aFailedConnectIsAnUnavailableRefusal() {
		assertThatThrownBy(() -> buckets.tryConsume("merchant:a")).isInstanceOfSatisfying(EdgeRefusalException.class, refusal -> {
			assertThat(refusal.code()).isEqualTo(ErrorCode.RATE_LIMITER_UNAVAILABLE);
			assertThat(refusal.retryAfter()).contains(MerchantBuckets.RETRY_AFTER);
			assertThat(refusal).hasCauseInstanceOf(RedisConnectionException.class);
		});
	}

	// An outage opens one socket per RETRY_AFTER, not one per request.
	@Test
	void aFailedConnectIsBelievedUntilRetryAfterHasPassed() {
		assertThatThrownBy(() -> buckets.tryConsume("merchant:a")).isInstanceOf(EdgeRefusalException.class);
		clock.addAndGet(MerchantBuckets.RETRY_AFTER.toNanos() - 1);
		assertThatThrownBy(() -> buckets.tryConsume("merchant:a")).isInstanceOf(EdgeRefusalException.class);
		assertThat(connects).hasValue(1);

		clock.addAndGet(1);
		assertThatThrownBy(() -> buckets.tryConsume("merchant:a")).isInstanceOf(EdgeRefusalException.class);
		assertThat(connects).hasValue(2);
	}

}
