package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

// One hot merchant hammered through two gateways on a healthy Redis: compare-and-swap conflicts are contention,
// answered 429, never the 503 outage that pages RateLimiterUnavailable. Retries are cut to one to force them.
class ContentionIT {

	private static final int REQUESTS = 96;

	@Test
	void contentionOnOneKeyIsNeverAnOutage() throws Exception {
		RedisStore store = RedisStore.started();
		List<String> limits = List.of("--ezeebit.gateway.rate-limit.capacity=1000", "--ezeebit.gateway.rate-limit.max-retries=1");
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext first = Gateways.start(store.args(), upstream.instances(), limits);
				ConfigurableApplicationContext second = Gateways.start(store.args(), upstream.instances(), limits);
				ExecutorService pool = Executors.newFixedThreadPool(48)) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			List<Future<Integer>> replies = new ArrayList<>();
			for (int i = 0; i < REQUESTS; i++) {
				ConfigurableApplicationContext gateway = i % 2 == 0 ? first : second;
				replies.add(pool.submit(() -> get(gateway, path).getStatusCode().value()));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> reply : replies) {
				statuses.add(reply.get());
			}
			Map<String, Double> one = Gateways.outcomes(first);
			Map<String, Double> two = Gateways.outcomes(second);

			assertThat(statuses).as("admitted or contended, never 503").containsOnly(200, 429);
			assertThat(one.get("unavailable") + two.get("unavailable")).isZero();
			assertThat(one.getOrDefault("contention", 0.0) + two.getOrDefault("contention", 0.0))
					.as("with one retry some compare-and-swaps must lose").isPositive();
			assertThat(statuses.stream().filter(status -> status == 429).count())
					.isEqualTo(Math.round(one.get("contention") + two.get("contention")));
		}
		finally {
			store.stop();
		}
	}

}
