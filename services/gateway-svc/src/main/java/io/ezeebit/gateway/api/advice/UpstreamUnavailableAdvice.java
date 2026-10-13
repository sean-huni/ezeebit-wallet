package io.ezeebit.gateway.api.advice;

import java.net.ConnectException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// Unhandled, these upstream failures reach the client as 500. Details stay fixed: no service names leak.
@RestControllerAdvice
public class UpstreamUnavailableAdvice {

	// No registered instance: the load balancer throws a 503.
	@ExceptionHandler(HttpServerErrorException.class)
	ProblemDetail upstreamFailed(final HttpServerErrorException ex) {
		return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), "Upstream service unavailable");
	}

	// Refused connection: the request never reached the service. Anything else (reset, timeout) may have.
	@ExceptionHandler(ResourceAccessException.class)
	ProblemDetail upstreamUnreachable(final ResourceAccessException ex) {
		if (ex.getMostSpecificCause() instanceof ConnectException) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable");
		}
		return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
				"Upstream outcome unknown: retry with the same Idempotency-Key");
	}

}
