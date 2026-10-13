package io.ezeebit.ledger.api.advice;

import io.ezeebit.ledger.common.LogText;
import io.ezeebit.ledger.exception.ErrorCode;
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

	public GlobalExceptionHandler(final ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(final Exception ex) {
		LOG.error("unhandled exception", LogText.sanitised(ex));
		return problems.of(ErrorCode.INTERNAL_ERROR);
	}

}
