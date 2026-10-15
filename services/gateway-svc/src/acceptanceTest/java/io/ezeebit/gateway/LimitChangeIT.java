package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

// A lowered capacity reaches merchants who are already active, the way a redeploy with new limits would.
class LimitChangeIT {

	@Test
	void anActiveMerchantIsHeldToTheNewCapacityAfterAChange() {
		RedisStore store = RedisStore.started();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext before = Gateways.start(store.args(), upstream.instances(), limits(10));
				ConfigurableApplicationContext after = Gateways.start(store.args(), upstream.instances(), limits(2))) {
			String path = "/api/v1/merchants/%s/balances".formatted(UUID.randomUUID());
			assertThat(get(before, path).getStatusCode().value()).as("the merchant is active under capacity 10").isEqualTo(200);

			int admitted = 0;
			for (int i = 0; i < 6; i++) {
				admitted += get(after, path).getStatusCode().value() == 200 ? 1 : 0;
			}

			assertThat(admitted).as("held to the new capacity of 2, not the 9 left under the old one").isLessThanOrEqualTo(2);
		}
		finally {
			store.stop();
		}
	}

	private static List<String> limits(final int capacity) {
		return List.of("--ezeebit.gateway.rate-limit.capacity=%d".formatted(capacity), "--ezeebit.gateway.rate-limit.refill-period=PT1H");
	}

}
