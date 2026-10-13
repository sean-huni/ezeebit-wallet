package io.ezeebit.gateway.api.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.ezeebit.gateway.common.RateLimitMetrics;
import io.ezeebit.gateway.config.properties.RateLimitProperties;
import io.ezeebit.gateway.exception.EdgeRefusalException;
import io.ezeebit.gateway.exception.ErrorCode;
import io.ezeebit.gateway.service.MerchantBuckets;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

	private static final RateLimitProperties LIMITS = new RateLimitProperties(10, 1, Duration.ofSeconds(1), 5,
			new RateLimitProperties.Store("localhost", 6379, "p", Duration.ofMillis(250), Duration.ofSeconds(10)));

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final List<Exception> resolved = new ArrayList<>();

	// A failure nobody anticipated in the limiter path is still a counted 503, never a 500 and never forwarded.
	@Test
	void anUnexpectedFailureIsACountedUnavailableRefusal() throws Exception {
		MerchantBuckets broken = new MerchantBuckets(() -> {
			throw new IllegalStateException("not a store error anyone classified");
		}, LIMITS, System::nanoTime);
		RateLimitFilter filter = new RateLimitFilter(broken, new RateLimitMetrics(registry), (request, response, handler, ex) -> {
			resolved.add(ex);
			return null;
		});
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/merchants/7f9c2a1e-3b4d-4c5e-8f6a-0b1c2d3e4f5a/balances"),
				new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).as("never forwarded").isNull();
		assertThat(resolved).singleElement().isInstanceOfSatisfying(EdgeRefusalException.class,
				refusal -> assertThat(refusal.code()).isEqualTo(ErrorCode.RATE_LIMITER_UNAVAILABLE));
		assertThat(registry.get(RateLimitMetrics.NAME).tag("outcome", "unavailable").counter().count()).isEqualTo(1);
	}

}
