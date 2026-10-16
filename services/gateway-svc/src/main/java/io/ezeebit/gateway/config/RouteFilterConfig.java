package io.ezeebit.gateway.config;

import io.ezeebit.gateway.api.filter.UnsentRetryFilterFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RouteFilterConfig {

	@Bean
	UnsentRetryFilterFunctions.FilterSupplier unsentRetryFilterSupplier() {
		return new UnsentRetryFilterFunctions.FilterSupplier();
	}

}
