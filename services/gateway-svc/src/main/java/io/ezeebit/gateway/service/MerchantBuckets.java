package io.ezeebit.gateway.service;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.exception.EdgeRefusalException;
import io.ezeebit.gateway.exception.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;

// One bucket per merchant in Redis, shared by every gateway replica: an in-heap bucket would make the limit
// capacity x replicas. Connected on first use, so a Redis that is away at boot is refused per request, not a
// failed start; a failed connect is believed for RETRY_AFTER so an outage opens one socket a second, not one per call.
public final class MerchantBuckets {

	public static final Duration RETRY_AFTER = Duration.ofSeconds(1);

	private static final String KEY_PREFIX = "gateway:ratelimit:";
	// Keys outlive a full refill by this, then expire: Redis forgets merchants who stopped calling.
	private static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(10);

	private final Supplier<StatefulRedisConnection<String, byte[]>> connect;
	private final BucketConfiguration limits;
	private final Duration timeout;
	private final LongSupplier nanos;
	private ProxyManager<String> buckets;
	private long retryAt;

	public MerchantBuckets(final Supplier<StatefulRedisConnection<String, byte[]>> connect, final RateLimitProperties properties,
			final LongSupplier nanos) {
		this.connect = connect;
		this.limits = BucketConfiguration.builder()
				.addLimit(Bandwidth.builder().capacity(properties.capacity())
						.refillGreedy(properties.refillTokens(), properties.refillPeriod()).build())
				.build();
		this.timeout = properties.store().timeout();
		this.nanos = nanos;
	}

	// A store that cannot be reached or does not answer is a refusal, never an admission.
	public ConsumptionProbe tryConsume(final String key) {
		try {
			return buckets().builder().build("%s%s".formatted(KEY_PREFIX, key), () -> limits).tryConsumeAndReturnRemaining(1);
		}
		catch (RedisException | BucketExceptions.BucketExecutionException ex) {
			throw unavailable(ex);
		}
	}

	// Synchronized only around obtaining the manager; the Redis round trip runs outside the lock.
	private synchronized ProxyManager<String> buckets() {
		if (buckets == null) {
			long now = nanos.getAsLong();
			if (now - retryAt < 0) {
				throw unavailable(null);
			}
			try {
				buckets = Bucket4jLettuce.casBasedBuilder(connect.get())
						.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
						.requestTimeout(timeout)
						.build();
			}
			catch (RedisException ex) {
				retryAt = now + RETRY_AFTER.toNanos();
				throw unavailable(ex);
			}
		}
		return buckets;
	}

	private static EdgeRefusalException unavailable(final Throwable cause) {
		return new EdgeRefusalException(ErrorCode.RATE_LIMITER_UNAVAILABLE, RETRY_AFTER, cause);
	}

}
