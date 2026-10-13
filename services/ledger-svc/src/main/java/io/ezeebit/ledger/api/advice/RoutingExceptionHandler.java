package io.ezeebit.ledger.api.advice;

import io.ezeebit.ledger.exception.ErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// The request never reached an endpoint: unknown path, version, method or media type.
@Order(2)
@RestControllerAdvice
public class RoutingExceptionHandler {

	private final ProblemFactory problems;

	public RoutingExceptionHandler(final ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ProblemDetail notFound(final NoResourceFoundException ex) {
		return problems.of(ErrorCode.NOT_FOUND);
	}

	@ExceptionHandler(InvalidApiVersionException.class)
	ProblemDetail invalidVersion(final InvalidApiVersionException ex) {
		return problems.of(ErrorCode.UNSUPPORTED_API_VERSION, ex.getVersion());
	}

	@ExceptionHandler(MissingApiVersionException.class)
	ProblemDetail missingVersion(final MissingApiVersionException ex) {
		return problems.of(ErrorCode.UNSUPPORTED_API_VERSION, "");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ProblemDetail methodNotAllowed(final HttpRequestMethodNotSupportedException ex) {
		return problems.of(ErrorCode.METHOD_NOT_ALLOWED, ex.getMethod());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ProblemDetail unsupportedMediaType(final HttpMediaTypeNotSupportedException ex) {
		return problems.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, String.valueOf(ex.getContentType()));
	}

}
