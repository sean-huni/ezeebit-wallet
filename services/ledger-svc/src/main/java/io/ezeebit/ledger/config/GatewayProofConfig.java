package io.ezeebit.ledger.config;

import io.ezeebit.ledger.api.filter.GatewayProofFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class GatewayProofConfig {

	// After Boot's RequestContextFilter (-105), so the refusal's text follows Accept-Language.
	private static final int FILTER_ORDER = 0;

	// No default: config-svc serves it, and a service that cannot get it fails to start rather than run open.
	@Bean
	FilterRegistrationBean<GatewayProofFilter> gatewayProofFilter(@Value("${ezeebit.gateway.shared-secret}") final String secret,
			@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
		FilterRegistrationBean<GatewayProofFilter> registration = new FilterRegistrationBean<>(new GatewayProofFilter(secret, resolver));
		registration.setOrder(FILTER_ORDER);
		return registration;
	}

}
