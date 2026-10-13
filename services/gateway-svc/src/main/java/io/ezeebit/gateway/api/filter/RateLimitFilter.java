package io.ezeebit.gateway.api.filter;

import java.io.IOException;
import java.time.Duration;

import io.ezeebit.gateway.common.MerchantPath;
import io.ezeebit.gateway.common.MerchantPath.Kind;
import io.ezeebit.gateway.common.RateLimitMetrics;
import io.ezeebit.gateway.common.RateLimitMetrics.Outcome;
import io.ezeebit.gateway.exception.EdgeRefusalException;
import io.ezeebit.gateway.exception.ErrorCode;
import io.ezeebit.gateway.service.MerchantBuckets;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// Runs before routing on every merchant path. Fails CLOSED: a store that cannot answer refuses the request,
// because admitting it would switch the limit off exactly when Redis is the thing under strain.
public final class RateLimitFilter extends OncePerRequestFilter {

	public static final String REMAINING = "X-RateLimit-Remaining";

	private final MerchantBuckets buckets;
	private final RateLimitMetrics metrics;
	private final HandlerExceptionResolver refusals;

	public RateLimitFilter(final MerchantBuckets buckets, final RateLimitMetrics metrics, final HandlerExceptionResolver refusals) {
		this.buckets = buckets;
		this.metrics = metrics;
		this.refusals = refusals;
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
			throws ServletException, IOException {
		MerchantPath path = MerchantPath.of(request.getRequestURI());
		switch (path.kind()) {
			case NOT_MERCHANT -> chain.doFilter(request, response);
			case MERCHANT -> limit(path.key(), request, response, chain);
			// Any other kind is refused, so a kind added later fails closed.
			default -> refuse(request, response, EdgeRefusalException.of(
					path.kind() == Kind.MALFORMED ? ErrorCode.MALFORMED_PATH : ErrorCode.INVALID_MERCHANT_ID));
		}
	}

	private void limit(final String key, final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain chain) throws ServletException, IOException {
		ConsumptionProbe probe;
		try {
			probe = buckets.tryConsume(key);
		}
		catch (EdgeRefusalException unavailable) {
			metrics.record(Outcome.UNAVAILABLE);
			refuse(request, response, unavailable);
			return;
		}
		if (!probe.isConsumed()) {
			metrics.record(Outcome.LIMITED);
			refuse(request, response, new EdgeRefusalException(ErrorCode.RATE_LIMITED,
					Duration.ofNanos(probe.getNanosToWaitForRefill()), null));
			return;
		}
		metrics.record(Outcome.ALLOWED);
		response.setHeader(REMAINING, Long.toString(probe.getRemainingTokens()));
		chain.doFilter(request, response);
	}

	private void refuse(final HttpServletRequest request, final HttpServletResponse response, final EdgeRefusalException refusal) {
		refusals.resolveException(request, response, null, refusal);
	}

}
