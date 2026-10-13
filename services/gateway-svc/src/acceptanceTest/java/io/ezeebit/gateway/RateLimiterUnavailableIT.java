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

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

// Fail closed: a store that cannot answer refuses the request; it is never forwarded unlimited.
class RateLimiterUnavailableIT {

	private static final Duration WITHIN = Duration.ofSeconds(2);

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

	private static void assertUnavailable(final ResponseEntity<String> refused) {
		assertThat(refused.getStatusCode().value()).as(refused.getBody()).isEqualTo(503);
		assertThat(code(refused)).isEqualTo("RATE_LIMITER_UNAVAILABLE");
		assertThat(header(refused, HttpHeaders.RETRY_AFTER)).isEqualTo("1");
	}

}
