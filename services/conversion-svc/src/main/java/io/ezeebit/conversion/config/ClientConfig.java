package io.ezeebit.conversion.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.domain.RateRules;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.outbound.discovery.DiscoveryNameResolverProvider;
import io.grpc.ManagedChannel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
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

	// round_robin over every instance the registry names: HTTP/2 otherwise pins all calls to one replica.
	// The resolver parameter only orders its registration before this channel is built. No usePlaintext(): the
	// factory passes ChannelCredentials (insecure without an SSL bundle) and the builder then refuses it.
	@Bean(destroyMethod = "shutdownNow")
	ManagedChannel ledgerChannel(final GrpcChannelFactory channels, final DiscoveryNameResolverProvider resolver,
			final ConversionProperties properties) {
		return channels.createChannel(properties.ledger().target(), ChannelBuilderOptions.defaults()
				.withCustomizer((target, builder) -> builder.defaultLoadBalancingPolicy("round_robin")));
	}

	private static JdkClientHttpRequestFactory factory(final Duration connect, final Duration read) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connect).build());
		factory.setReadTimeout(read);
		return factory;
	}

}
