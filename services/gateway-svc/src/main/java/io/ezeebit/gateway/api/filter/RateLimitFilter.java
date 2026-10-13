package io.ezeebit.gateway.api.filter;

import java.io.IOException;

import io.ezeebit.gateway.api.filter.MerchantPath.Kind;
import io.ezeebit.gateway.common.metrics.RateLimitMetrics;
import io.ezeebit.gateway.domain.Admission;
import io.ezeebit.gateway.domain.LimitOutcome;
import io.ezeebit.gateway.exception.EdgeRefusalException;
import io.ezeebit.gateway.exception.ErrorCode;
import io.ezeebit.gateway.service.MerchantBuckets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// Runs before routing on every request. Fails CLOSED: a store that cannot answer refuses the request,
// because admitting it would switch the limit off exactly when Redis is the thing under strain.
public final class RateLimitFilter extends OncePerRequestFilter {

	public static final String REMAINING = "X-RateLimit-Remaining";

	private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

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
			// Any other kind is refused and counted, so probing leaves a trace and a kind added later fails closed.
			default -> {
				metrics.record(LimitOutcome.REFUSED_PATH);
				refuse(request, response, EdgeRefusalException.of(
						path.kind() == Kind.MALFORMED ? ErrorCode.MALFORMED_PATH : ErrorCode.INVALID_MERCHANT_ID));
			}
		}
	}

	private void limit(final String key, final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain chain) throws ServletException, IOException {
		Admission admission = metrics.decide(() -> admit(key));
		if (admission.outcome() != LimitOutcome.ALLOWED) {
			LOG.info("merchant request refused at the edge: {}", admission.outcome());
		}
		switch (admission.outcome()) {
			case ALLOWED -> {
				response.setHeader(REMAINING, Long.toString(admission.remaining()));
				chain.doFilter(request, response);
			}
			case UNAVAILABLE -> refuse(request, response,
					new EdgeRefusalException(ErrorCode.RATE_LIMITER_UNAVAILABLE, admission.retryAfter(), null));
			default -> refuse(request, response, new EdgeRefusalException(ErrorCode.RATE_LIMITED, admission.retryAfter(), null));
		}
	}

	// The last line of fail-closed: a failure nobody classified is still a counted 503, never a 500 or a pass-through.
	private Admission admit(final String key) {
		try {
			return buckets.tryConsume(key);
		}
		catch (RuntimeException unexpected) {
			LOG.error("rate limiter failed unexpectedly; the request is refused with 503", unexpected);
			return Admission.unavailable(MerchantBuckets.RETRY_AFTER);
		}
	}

	private void refuse(final HttpServletRequest request, final HttpServletResponse response, final EdgeRefusalException refusal) {
		refusals.resolveException(request, response, null, refusal);
	}

}
