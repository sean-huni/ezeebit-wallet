package io.ezeebit.gateway.config;

import java.util.List;
import java.util.stream.Stream;

import io.ezeebit.gateway.api.filter.UnsentRetryFilterFunctions;
import io.ezeebit.gateway.common.metrics.UpstreamResendMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RouteFilterConfig {

	@Bean
	UnsentRetryFilterFunctions.FilterSupplier unsentRetryFilterSupplier() {
		return new UnsentRetryFilterFunctions.FilterSupplier();
	}

	// The routes come from the served table, so a route that gains RetryUnsent gains its zero series with it.
	@Bean
	UpstreamResendMetrics upstreamResendMetrics(final MeterRegistry registry, final GatewayMvcProperties gateway) {
		List<String> routes = Stream.concat(gateway.getRoutes().stream(), gateway.getRoutesMap().values().stream())
				.filter(RouteFilterConfig::resends)
				.map(route -> String.valueOf(route.getId()))
				.toList();
		return new UpstreamResendMetrics(registry, routes);
	}

	private static boolean resends(final RouteProperties route) {
		return route.getFilters().stream().anyMatch(filter -> UnsentRetryFilterFunctions.NAME.equals(filter.getName()));
	}

}
