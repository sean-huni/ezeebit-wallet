package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

// Two gateway contexts on one Redis stand in for two replicas behind one Service.
class SharedBucketIT {

	private static final int CAPACITY = 4;

	@Test
	void aBurstSplitAcrossTwoGatewaysAdmitsAtMostCapacity() {
		RedisStore store = RedisStore.started();
		List<String> limits = List.of("--ezeebit.gateway.rate-limit.capacity=%d".formatted(CAPACITY),
				"--ezeebit.gateway.rate-limit.refill-period=PT1H");
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext first = Gateways.start(store.args(), upstream.instances(), limits);
				ConfigurableApplicationContext second = Gateways.start(store.args(), upstream.instances(), limits)) {
			String path = "/api/v1/merchants/%s/conversions/c-1".formatted(UUID.randomUUID());
			int admitted = 0;
			int limited = 0;
			for (int i = 0; i < 2 * CAPACITY; i++) {
				int status = get(i % 2 == 0 ? first : second, path).getStatusCode().value();
				admitted += status == 200 ? 1 : 0;
				limited += status == 429 ? 1 : 0;
			}

			assertThat(admitted).as("per-replica buckets would admit capacity x replicas = %d", 2 * CAPACITY).isEqualTo(CAPACITY);
			assertThat(limited).isEqualTo(CAPACITY);
			assertThat(upstream.hits()).isEqualTo(CAPACITY);
			assertThat(get(second, "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID())).getStatusCode().value())
					.as("control: the second gateway admits a merchant with tokens left").isEqualTo(200);
		}
		finally {
			store.stop();
		}
	}

}
