package io.ezeebit.conversion.api.advice;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.ezeebit.conversion.exception.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException.Reference;
import tools.jackson.databind.exc.MismatchedInputException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ValidationExceptionHandler {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private final ProblemFactory problems;
	private final ViolationReader violations;

	public ValidationExceptionHandler(final ProblemFactory problems, final ViolationReader violations) {
		this.problems = problems;
		this.violations = violations;
	}

	@ExceptionHandler(BindException.class)
	ProblemDetail invalidBody(final BindException ex) {
		return problems.invalid(ex.getFieldErrors().stream().map(violations::of).toList());
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail invalidParameters(final HandlerMethodValidationException ex) {
		return problems.invalid(violations.of(ex.getParameterValidationResults()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail typeMismatch(final MethodArgumentTypeMismatchException ex) {
		return problems.invalid(List.of(violations.typeMismatch(ex.getName())));
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail missingHeader(final MissingRequestHeaderException ex) {
		if (IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())) {
			return problems.of(ErrorCode.IDEMPOTENCY_KEY_MISSING);
		}
		return problems.invalid(List.of(violations.typeMismatch(ex.getHeaderName())));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ProblemDetail missingParameter(final MissingServletRequestParameterException ex) {
		return problems.invalid(List.of(violations.typeMismatch(ex.getParameterName())));
	}

	// Valid JSON with a value of the wrong type names its field; anything else is malformed.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(final HttpMessageNotReadableException ex) {
		if (ex.getMostSpecificCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
			String field = mismatch.getPath().stream().map(Reference::getPropertyName).filter(Objects::nonNull)
					.collect(Collectors.joining("."));
			return problems.invalid(List.of(violations.typeMismatch(field)));
		}
		return problems.of(ErrorCode.MALFORMED_REQUEST);
	}

}
