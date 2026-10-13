package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.code;
import static io.ezeebit.gateway.Gateways.get;
import static io.ezeebit.gateway.Gateways.header;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.service.MerchantBuckets;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

// Fail closed: a store that cannot answer refuses the request; it is never forwarded unlimited.
class RateLimiterUnavailableIT {

	private static final Duration WITHIN = Duration.ofSeconds(2);
	private static final Duration RECOVERY = Duration.ofMillis(2500);
	private static final Duration GIVE_UP = Duration.ofSeconds(15);
	private static final Duration DOWN = Duration.ofSeconds(6);
	private static final Duration POLL = Duration.ofMillis(100);
	private static final Duration GAUGE_MARGIN = Duration.ofSeconds(3);
	private static final String STORE_UP = "ezeebit.gateway.ratelimit.store.up";

	@Test
	void aStoreStoppedMidRunRefusesWith503AndForwardsNothing() {
		RedisStore store = RedisStore.started();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances())) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			assertThat(get(gateway, path).getStatusCode().value()).as("control: admitted while the store answers").isEqualTo(200);
			int forwarded = upstream.hits();
			Map<String, Double> before = Gateways.outcomes(gateway);

			store.kill();
			ResponseEntity<String> refused = get(gateway, path);

			assertUnavailable(refused);
			assertThat(upstream.hits()).isEqualTo(forwarded);
			assertThat(Gateways.outcomes(gateway)).containsEntry("unavailable", before.get("unavailable") + 1);
		}
		finally {
			store.stop();
		}
	}

	@Test
	void aStoreThatStopsAnsweringIsRefusedWithinTheCommandTimeout() {
		RedisStore store = RedisStore.started();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances())) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			assertThat(get(gateway, path).getStatusCode().value()).isEqualTo(200);
			int forwarded = upstream.hits();

			store.pause();
			try {
				long started = System.nanoTime();
				ResponseEntity<String> refused = get(gateway, path);
				Duration waited = Duration.ofNanos(System.nanoTime() - started);

				assertUnavailable(refused);
				assertThat(waited).as("bounded by the 250ms store timeout, not by a client default").isLessThan(WITHIN);
				assertThat(upstream.hits()).isEqualTo(forwarded);
			}
			finally {
				store.unpause();
			}
		}
		finally {
			store.stop();
		}
	}

	@Test
	void aGatewayBootsWithoutItsStoreAndRefusesUntilItAnswers() throws IOException {
		int nothingListens;
		try (ServerSocket probe = new ServerSocket(0)) {
			nothingListens = probe.getLocalPort();
		}
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(upstream.instances(),
						List.of("--ezeebit.gateway.rate-limit.store.host=127.0.0.1",
								"--ezeebit.gateway.rate-limit.store.port=%d".formatted(nothingListens)))) {
			assertUnavailable(get(gateway, "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID())));
			assertThat(upstream.hits()).isZero();
			assertThat(get(gateway, "/actuator/health").getStatusCode().value())
					.as("the refusal is the application's, not the probe's: the gateway stays in rotation").isEqualTo(200);
		}
	}

	// The connect back-off is 1s, so a store that appears after boot is used within it plus one connect.
	@Test
	void aStoreThatComesUpAfterBootIsUsedWithinTheBackOff() {
		RedisStore store = RedisStore.held();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances())) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			assertUnavailable(get(gateway, path));

			store.serve();

			assertThat(firstAdmission(gateway, path)).as("admitted after the store came up").isLessThan(RECOVERY);
		}
		finally {
			store.stop();
		}
	}

	// Lettuce's own reconnect backs off to 30s by default; capped at 1s, a restarted store is used at once.
	@Test
	void aKilledStoreIsUsedAgainAfterItRestarts() throws InterruptedException {
		RedisStore store = RedisStore.held();
		store.serve();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances())) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			assertThat(get(gateway, path).getStatusCode().value()).isEqualTo(200);

			store.drop();
			Thread.sleep(DOWN.toMillis());
			assertUnavailable(get(gateway, path));
			store.serve();

			assertThat(firstAdmission(gateway, path)).as("admitted after the store restarted").isLessThan(RECOVERY);
		}
		finally {
			store.stop();
		}
	}

	@Test
	void theStoreHealthGaugeFollowsTheStore() throws InterruptedException {
		RedisStore store = RedisStore.held();
		store.serve();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances(),
						List.of("--ezeebit.gateway.rate-limit.store.probe-interval=200ms"))) {
			assertThat(awaitStoreUp(gateway, 1.0)).as("up while the store answers PING").isTrue();

			store.drop();
			assertThat(awaitStoreUp(gateway, 0.0)).as("down once PING fails").isTrue();

			store.serve();
			assertThat(awaitStoreUp(gateway, 1.0)).as("up again after the store restarts").isTrue();
		}
		finally {
			store.stop();
		}
	}

	private static Duration firstAdmission(final ConfigurableApplicationContext gateway, final String path) {
		long started = System.nanoTime();
		Duration waited = Duration.ZERO;
		while (waited.compareTo(GIVE_UP) < 0) {
			if (get(gateway, path).getStatusCode().value() == 200) {
				return waited;
			}
			sleep(POLL);
			waited = Duration.ofNanos(System.nanoTime() - started);
		}
		return waited;
	}

	private static boolean awaitStoreUp(final ConfigurableApplicationContext gateway, final double expected) throws InterruptedException {
		long deadline = System.nanoTime() + gaugeBudget(gateway).toNanos();
		while (System.nanoTime() - deadline < 0) {
			Double up = gateway.getBean(MeterRegistry.class).find(STORE_UP).gauges().stream().findFirst()
					.map(gauge -> gauge.value()).orElse(null);
			if (up != null && up == expected) {
				return true;
			}
			Thread.sleep(POLL.toMillis());
		}
		return false;
	}

	// From the running gateway's own settings: the next probe, a connect and a PING each cut at the store timeout,
	// one reconnect back-off, and a margin for a loaded JVM and Docker VM.
	private static Duration gaugeBudget(final ConfigurableApplicationContext gateway) {
		RateLimitProperties.Store store = gateway.getBean(RateLimitProperties.class).store();
		return store.probeInterval().plus(store.timeout().multipliedBy(2)).plus(MerchantBuckets.RETRY_AFTER).plus(GAUGE_MARGIN);
	}

	private static void sleep(final Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void assertUnavailable(final ResponseEntity<String> refused) {
		assertThat(refused.getStatusCode().value()).as(refused.getBody()).isEqualTo(503);
		assertThat(code(refused)).isEqualTo("RATE_LIMITER_UNAVAILABLE");
		assertThat(header(refused, HttpHeaders.RETRY_AFTER)).isEqualTo("1");
	}

}
