package io.ezeebit.conversion.api.advice;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.exception.ErrorCode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Last line of defence only: anything the categorised handlers did not claim.
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ProblemFactory problems;
	private final ObservationRegistry observations;

	public GlobalExceptionHandler(final ProblemFactory problems, final ObservationRegistry observations) {
		this.problems = problems;
		this.observations = observations;
	}

	// An answered 500 never leaves the request, so it is put on the request's span here or Tempo shows no exception.
	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(final Exception ex) {
		LOG.error("unhandled exception", LogText.sanitised(ex));
		Observation request = observations.getCurrentObservation();
		if (request != null) {
			request.error(ex);
		}
		return problems.of(ErrorCode.INTERNAL_ERROR);
	}

}
