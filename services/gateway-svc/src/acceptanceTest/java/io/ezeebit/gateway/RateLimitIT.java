package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.code;
import static io.ezeebit.gateway.Gateways.get;
import static io.ezeebit.gateway.Gateways.header;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.gateway.api.filter.RateLimitFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class RateLimitIT {

	private static final int CAPACITY = 3;

	private static RedisStore store;
	private static UpstreamStub upstream;
	private static ConfigurableApplicationContext gateway;

	@BeforeAll
	static void start() {
		store = RedisStore.started();
		upstream = UpstreamStub.start();
		gateway = Gateways.start(store.args(), upstream.instances(), List.of("--ezeebit.gateway.rate-limit.capacity=%d".formatted(CAPACITY),
				"--ezeebit.gateway.rate-limit.refill-period=PT1H"));
	}

	@AfterAll
	static void stop() {
		gateway.close();
		upstream.close();
		store.stop();
	}

	@Test
	void aBurstAdmitsExactlyCapacityThenAnswers429() {
		String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
		int forwarded = upstream.hits();
		Map<String, Double> before = Gateways.outcomes(gateway);

		for (int i = 0; i < CAPACITY; i++) {
			ResponseEntity<String> admitted = get(gateway, path);
			assertThat(admitted.getStatusCode().value()).as("request %d of %d", i + 1, CAPACITY).isEqualTo(200);
			assertThat(header(admitted, RateLimitFilter.REMAINING)).isEqualTo(Integer.toString(CAPACITY - 1 - i));
		}
		ResponseEntity<String> limited = get(gateway, path);

		assertThat(limited.getStatusCode().value()).isEqualTo(429);
		assertThat(code(limited)).isEqualTo("RATE_LIMITED");
		// 20 tokens an hour here, so the next one is at most 180 s away.
		assertThat(Long.parseLong(header(limited, HttpHeaders.RETRY_AFTER))).isBetween(1L, 180L);
		assertThat(upstream.hits()).as("the limited request is never forwarded").isEqualTo(forwarded + CAPACITY);
		assertThat(Gateways.outcomes(gateway)).containsEntry("allowed", before.get("allowed") + CAPACITY)
				.containsEntry("limited", before.get("limited") + 1).containsEntry("unavailable", before.get("unavailable"));
	}

	@Test
	void eachMerchantHasItsOwnBucket() {
		String exhausted = "/api/v1/merchants/%s/quotes".formatted(UUID.randomUUID());
		for (int i = 0; i <= CAPACITY; i++) {
			get(gateway, exhausted);
		}

		assertThat(get(gateway, exhausted).getStatusCode().value()).isEqualTo(429);
		assertThat(get(gateway, "/api/v1/merchants/%s/quotes".formatted(UUID.randomUUID())).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void aNonUuidMerchantIsRefusedBeforeRedisIsTouched() {
		String keys = store.cli("DBSIZE");
		int forwarded = upstream.hits();

		ResponseEntity<String> refused = get(gateway, "/api/v1/merchants/not-a-uuid/balances");

		assertThat(refused.getStatusCode().value()).isEqualTo(400);
		assertThat(code(refused)).isEqualTo("INVALID_MERCHANT_ID");
		assertThat(store.cli("DBSIZE")).as("no key was written for a client-chosen string").isEqualTo(keys);
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

	// The route matcher ignores ;params, so this would reach the ledger while charged to no merchant's bucket.
	@Test
	void aMatrixParameterCannotStepAroundAnExhaustedBucket() {
		UUID merchant = UUID.randomUUID();
		for (int i = 0; i <= CAPACITY; i++) {
			get(gateway, "/api/v1/merchants/%s/balances".formatted(merchant));
		}
		int forwarded = upstream.hits();

		ResponseEntity<String> dodged = get(gateway, "/api/v1/merchants;x=1/%s/balances".formatted(merchant));

		assertThat(dodged.getStatusCode().value()).isEqualTo(400);
		assertThat(code(dodged)).isEqualTo("MALFORMED_PATH");
		assertThat(upstream.hits()).isEqualTo(forwarded);
	}

	@Test
	void aBucketKeyCarriesATimeToLive() {
		UUID merchant = UUID.randomUUID();
		get(gateway, "/api/v1/merchants/%s/balances".formatted(merchant));

		long ttl = Long.parseLong(store.cli("PTTL", "gateway:ratelimit:merchant:%s".formatted(merchant)));

		assertThat(ttl).as("-1 is no expiry, -2 is no key").isPositive();
	}

	@Test
	void routesOutsideTheMerchantPathAreNotLimited() {
		assertThat(get(gateway, "/actuator/health").getStatusCode().value()).isEqualTo(200);
		assertThat(get(gateway, "/internal/v1/deposits").getStatusCode().value()).isEqualTo(404);
	}

}
