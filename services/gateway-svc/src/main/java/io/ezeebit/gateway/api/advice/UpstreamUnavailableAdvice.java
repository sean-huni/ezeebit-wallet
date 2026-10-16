package io.ezeebit.gateway.api.advice;

import io.ezeebit.gateway.api.filter.UnsentRetryFilterFunctions;
import io.ezeebit.gateway.exception.ErrorCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// Unhandled, these upstream failures reach the client as 500. Details stay fixed: no service names leak.
@RestControllerAdvice
public class UpstreamUnavailableAdvice {

	private final ProblemFactory problems;

	public UpstreamUnavailableAdvice(final ProblemFactory problems) {
		this.problems = problems;
	}

	// No registered instance: the load balancer throws a 503.
	@ExceptionHandler(HttpServerErrorException.class)
	ProblemDetail upstreamFailed(final HttpServerErrorException ex) {
		return problems.of(ErrorCode.UPSTREAM_UNAVAILABLE);
	}

	// A failed connect (refused or timed out): the request never reached the service. Anything else (reset, read
	// timeout) may have. One definition of "never sent", shared with the RetryUnsent filter.
	@ExceptionHandler(ResourceAccessException.class)
	ProblemDetail upstreamUnreachable(final ResourceAccessException ex) {
		return problems.of(UnsentRetryFilterFunctions.neverSent(ex) ? ErrorCode.UPSTREAM_UNAVAILABLE
				: ErrorCode.UPSTREAM_OUTCOME_UNKNOWN);
	}

}
