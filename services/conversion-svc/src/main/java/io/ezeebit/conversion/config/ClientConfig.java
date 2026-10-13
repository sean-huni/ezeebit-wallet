package io.ezeebit.conversion.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.domain.RateRules;
import io.ezeebit.conversion.domain.RateValidator;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ConversionProperties.class)
public class ClientConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	RateValidator rateValidator(final ConversionProperties properties) {
		return new RateValidator(new RateRules(properties.rates().maxAge(), properties.rates().maxJumpPercent()));
	}

	// Not load-balanced: the rate service is an external URL, not a registry name. Observed, so each call is a client
	// span that sends the caller's trace on (traceparent) and an http.client.requests timer.
	@Bean
	RestClient rateRestClient(final ConversionProperties properties, final ObservationRegistry observations) {
		ConversionProperties.Rates rates = properties.rates();
		return RestClient.builder().baseUrl(rates.url()).observationRegistry(observations)
				.requestFactory(factory(rates.connectTimeout(), rates.readTimeout())).build();
	}

	// The load balancer resolves the registry name. No @LoadBalanced RestClient.Builder bean: Eureka's own client
	// would pick that builder up and try to resolve the registry's address through itself.
	@Bean
	RestClient ledgerRestClient(final DeferringLoadBalancerInterceptor loadBalancer,
			final ConversionProperties properties, final ObservationRegistry observations) {
		ConversionProperties.Ledger ledger = properties.ledger();
		return RestClient.builder().baseUrl(ledger.url()).requestInterceptor(loadBalancer).observationRegistry(observations)
				.requestFactory(factory(ledger.connectTimeout(), ledger.readTimeout())).build();
	}

	private static JdkClientHttpRequestFactory factory(final Duration connect, final Duration read) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build());
		factory.setReadTimeout(read);
		return factory;
	}

}
