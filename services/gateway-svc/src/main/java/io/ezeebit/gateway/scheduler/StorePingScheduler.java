package io.ezeebit.gateway.scheduler;

import io.ezeebit.gateway.service.MerchantBuckets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;

// ezeebit_gateway_ratelimit_store_up: 1 while Redis answers PING within the store timeout, else 0. This replica's
// view of a shared dependency, so dashboards and RateLimiterStoreDown read it with min() across replicas.
public final class StorePingScheduler {

	public static final String STORE_UP = "ezeebit.gateway.ratelimit.store.up";

	private final MerchantBuckets buckets;
	private volatile int up;

	public StorePingScheduler(final MerchantBuckets buckets, final MeterRegistry registry) {
		this.buckets = buckets;
		Gauge.builder(STORE_UP, this, probe -> probe.up)
				.description("1 while the rate-limit store answers PING within its timeout, else 0")
				.register(registry);
	}

	@Scheduled(fixedDelayString = "${ezeebit.gateway.rate-limit.store.probe-interval}")
	public void ping() {
		up = buckets.ping() ? 1 : 0;
	}

}
