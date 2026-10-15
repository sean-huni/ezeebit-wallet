package io.ezeebit.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.domain.Admission;
import io.ezeebit.gateway.domain.LimitOutcome;
import io.github.bucket4j.BucketExceptions;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.Test;

class MerchantBucketsTest {

	private static final RateLimitProperties LIMITS = new RateLimitProperties(10, 1, Duration.ofSeconds(1), 5,
			new RateLimitProperties.Store("localhost", 6379, "p", Duration.ofMillis(250), Duration.ofSeconds(10)));

	private final AtomicLong clock = new AtomicLong(1_000_000_000L);
	private final AtomicInteger connects = new AtomicInteger();
	private final MerchantBuckets buckets = new MerchantBuckets(() -> {
		connects.incrementAndGet();
		throw new RedisConnectionException("refused");
	}, LIMITS, clock::get);

	@Test
	void aFailedConnectIsUnavailableNeverAdmitted() {
		Admission admission = buckets.tryConsume("merchant:a");

		assertThat(admission.outcome()).isEqualTo(LimitOutcome.UNAVAILABLE);
		assertThat(admission.retryAfter()).isEqualTo(MerchantBuckets.RETRY_AFTER);
		assertThat(buckets.ping()).as("the health probe reads the same failure").isFalse();
	}

	@Test
	void aStoreErrorAnywhereInTheChainIsAnOutageAndALostRaceIsContention() {
		assertThat(MerchantBuckets.failed(new RedisCommandTimeoutException("stalled")).outcome()).isEqualTo(LimitOutcome.UNAVAILABLE);
		assertThat(MerchantBuckets.failed(new IllegalStateException(new RedisConnectionException("reset"))).outcome())
				.isEqualTo(LimitOutcome.UNAVAILABLE);
		assertThat(MerchantBuckets.failed(BucketExceptions.maxRetriesExceeded(1)).outcome()).isEqualTo(LimitOutcome.CONTENTION);
	}

	// An outage opens one socket per RETRY_AFTER, not one per request.
	@Test
	void aFailedConnectIsBelievedUntilRetryAfterHasPassed() {
		assertThat(buckets.tryConsume("merchant:a").outcome()).isEqualTo(LimitOutcome.UNAVAILABLE);
		clock.addAndGet(MerchantBuckets.RETRY_AFTER.toNanos() - 1);
		assertThat(buckets.tryConsume("merchant:a").outcome()).isEqualTo(LimitOutcome.UNAVAILABLE);
		assertThat(connects).hasValue(1);

		clock.addAndGet(1);
		assertThat(buckets.tryConsume("merchant:a").outcome()).isEqualTo(LimitOutcome.UNAVAILABLE);
		assertThat(connects).hasValue(2);
	}

}
