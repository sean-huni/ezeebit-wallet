package io.ezeebit.gateway.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.ezeebit.gateway.api.filter.RateLimitFilter;
import io.ezeebit.gateway.common.metrics.RateLimitMetrics;
import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.scheduler.StorePingScheduler;
import io.ezeebit.gateway.service.MerchantBuckets;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@EnableScheduling
class RateLimitConfig {

	// After Boot's RequestContextFilter (-105), so the refusal's text follows Accept-Language.
	private static final int FILTER_ORDER = 0;
	private static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

	// Lettuce's default reconnect backoff grows to 30s, which would keep refusing long after Redis is back.
	@Bean(destroyMethod = "shutdown")
	ClientResources rateLimitClientResources() {
		return DefaultClientResources.builder()
				.reconnectDelay(Delay.exponential(Duration.ofMillis(10), MerchantBuckets.RETRY_AFTER, 2, TimeUnit.MILLISECONDS))
				.build();
	}

	@Bean(destroyMethod = "shutdown")
	RedisClient rateLimitRedis(final ClientResources rateLimitClientResources, final RateLimitProperties properties) {
		Duration timeout = properties.store().timeout();
		RedisClient client = RedisClient.create(rateLimitClientResources);
		client.setOptions(ClientOptions.builder()
				.timeoutOptions(TimeoutOptions.enabled(timeout))
				.socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
				// A dropped connection fails each command at once instead of queueing it behind the reconnect.
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
				.build());
		return client;
	}

	@Bean
	MerchantBuckets merchantBuckets(final RedisClient rateLimitRedis, final RateLimitProperties properties) {
		RateLimitProperties.Store store = properties.store();
		RedisURI uri = RedisURI.builder().withHost(store.host()).withPort(store.port())
				.withPassword(store.password().toCharArray()).withTimeout(store.timeout()).build();
		return new MerchantBuckets(() -> rateLimitRedis.connect(CODEC, uri), properties, System::nanoTime);
	}

	@Bean
	StorePingScheduler storePingScheduler(final MerchantBuckets merchantBuckets, final MeterRegistry registry) {
		return new StorePingScheduler(merchantBuckets, registry);
	}

	@Bean
	RateLimitMetrics rateLimitMetrics(final MeterRegistry registry, final ObservationRegistry observations) {
		return new RateLimitMetrics(registry, observations);
	}

	@Bean
	FilterRegistrationBean<RateLimitFilter> rateLimitFilter(final MerchantBuckets merchantBuckets, final RateLimitMetrics rateLimitMetrics,
			@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
		FilterRegistrationBean<RateLimitFilter> registration =
				new FilterRegistrationBean<>(new RateLimitFilter(merchantBuckets, rateLimitMetrics, resolver));
		registration.setOrder(FILTER_ORDER);
		return registration;
	}

}
