package io.ezeebit.gateway.service;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.domain.Admission;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;

// One bucket per merchant in Redis, shared by every gateway replica: an in-heap bucket would make the limit
// capacity x replicas. Connected on first use, so a Redis that is away at boot is refused per request, not a
// failed start; a failed connect is believed for RETRY_AFTER so an outage opens one socket a second, not one per call.
public final class MerchantBuckets {

	public static final Duration RETRY_AFTER = Duration.ofSeconds(1);

	// Keys outlive a full refill by this, then expire: Redis forgets merchants who stopped calling.
	private static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(10);
	private static final String PONG = "PONG";

	private final Supplier<StatefulRedisConnection<String, byte[]>> connect;
	private final BucketConfiguration limits;
	private final String keyPrefix;
	private final RateLimitProperties properties;
	private final LongSupplier nanos;
	private volatile StatefulRedisConnection<String, byte[]> connection;
	private volatile ProxyManager<String> buckets;
	private long retryAt;

	public MerchantBuckets(final Supplier<StatefulRedisConnection<String, byte[]>> connect, final RateLimitProperties properties,
			final LongSupplier nanos) {
		this.connect = connect;
		this.properties = properties;
		this.nanos = nanos;
		this.limits = BucketConfiguration.builder()
				.addLimit(Bandwidth.builder().capacity(properties.capacity())
						.refillGreedy(properties.refillTokens(), properties.refillPeriod()).build())
				.build();
		// The limits are part of the key: Bucket4j replaces a stored configuration only for a HIGHER version, which a
		// value derived from the limits cannot promise, so a changed limit gets its own bucket instead.
		this.keyPrefix = "gateway:ratelimit:%d-%d-%d:".formatted(properties.capacity(), properties.refillTokens(),
				properties.refillPeriod().toMillis());
	}

	public Admission tryConsume(final String key) {
		try {
			ConsumptionProbe probe = buckets().builder().build("%s%s".formatted(keyPrefix, key), () -> limits)
					.tryConsumeAndReturnRemaining(1);
			return probe.isConsumed() ? Admission.allowed(probe.getRemainingTokens())
					: Admission.limited(Duration.ofNanos(probe.getNanosToWaitForRefill()));
		}
		catch (RedisException | BucketExceptions.BucketExecutionException ex) {
			return failed(ex);
		}
	}

	// A store error anywhere in the chain (refused, reset, command timeout, auth) is an outage; a compare-and-swap
	// loop that ran out of retries on a healthy store is contention.
	static Admission failed(final RuntimeException failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof RedisException) {
				return Admission.unavailable(RETRY_AFTER);
			}
		}
		return Admission.contention(RETRY_AFTER);
	}

	// The store-health probe: true only when PING answers PONG within the store timeout.
	public boolean ping() {
		try {
			buckets();
			return PONG.equals(connection.sync().ping());
		}
		catch (RedisException ex) {
			return false;
		}
	}

	private ProxyManager<String> buckets() {
		ProxyManager<String> ready = buckets;
		return ready != null ? ready : open();
	}

	private synchronized ProxyManager<String> open() {
		if (buckets == null) {
			long now = nanos.getAsLong();
			if (now - retryAt < 0) {
				throw new RedisConnectionException("rate-limit store connect failed less than %s ago".formatted(RETRY_AFTER));
			}
			try {
				connection = connect.get();
			}
			catch (RedisException ex) {
				retryAt = now + RETRY_AFTER.toNanos();
				throw ex;
			}
			// Each command is cut at the store timeout; retries bound a compare-and-swap loop that keeps losing.
			buckets = Bucket4jLettuce.casBasedBuilder(connection)
					.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
					.maxRetries(properties.maxRetries())
					.build();
		}
		return buckets;
	}

}
