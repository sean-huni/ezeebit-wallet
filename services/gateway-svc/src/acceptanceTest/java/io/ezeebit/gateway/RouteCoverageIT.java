package io.ezeebit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.ezeebit.gateway.common.MerchantPath;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.PredicateProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.context.ConfigurableApplicationContext;

// No route can be reached without being charged: every Path pattern of the served route table, made concrete,
// is a path the limiter keys on a merchant. A route added outside /api/v1/merchants/{id}/ fails this test.
class RouteCoverageIT {

	@Test
	void everyPublishedRouteIsChargedToAMerchantBucket() {
		RedisStore store = RedisStore.started();
		try (UpstreamStub upstream = UpstreamStub.start();
				ConfigurableApplicationContext gateway = Gateways.start(store.args(), upstream.instances())) {
			GatewayMvcProperties table = gateway.getBean(GatewayMvcProperties.class);
			List<String> patterns = Stream.concat(table.getRoutes().stream(), table.getRoutesMap().values().stream())
					.flatMap(route -> paths(route).stream()).toList();

			assertThat(patterns).as("the served table publishes the four merchant resources").hasSize(4);
			String merchant = UUID.randomUUID().toString();
			assertThat(patterns).allSatisfy(pattern -> {
				String concrete = pattern.replace("/**", "/x").replace("*", merchant);
				assertThat(MerchantPath.of(concrete)).as(pattern)
						.isEqualTo(new MerchantPath(MerchantPath.Kind.MERCHANT, "merchant:%s".formatted(merchant)));
			});
		}
		finally {
			store.stop();
		}
	}

	private static List<String> paths(final RouteProperties route) {
		List<String> paths = new ArrayList<>();
		for (PredicateProperties predicate : route.getPredicates()) {
			assertThat(predicate.getName()).as("route %s: only Path predicates are understood here", route.getId()).isEqualTo("Path");
			predicate.getArgs().values().forEach(value -> paths.addAll(List.of(value.split(","))));
		}
		return paths;
	}

}
