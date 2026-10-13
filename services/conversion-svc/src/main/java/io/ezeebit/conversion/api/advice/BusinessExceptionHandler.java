package io.ezeebit.conversion.api.advice;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.exception.ConversionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(1)
@RestControllerAdvice
public class BusinessExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(BusinessExceptionHandler.class);

	private final ProblemFactory problems;

	public BusinessExceptionHandler(final ProblemFactory problems) {
		this.problems = problems;
	}

	// A 502 or 503 is a dependency refusing or failing: worth a warning, not an invariant breach.
	@ExceptionHandler(ConversionException.class)
	ProblemDetail business(final ConversionException ex) {
		if (ErrorStatus.of(ex.code()).is5xxServerError()) {
			LOG.warn("conversion refused: {} {}", LogText.oneLine(ex.getMessage()), LogText.oneLine(ex.properties()));
		}
		ProblemDetail problem = problems.of(ex.code(), ex.args());
		if (ex.field() != null) {
			problem.setProperty("field", ex.field());
		}
		ex.properties().forEach(problem::setProperty);
		return problem;
	}

}
