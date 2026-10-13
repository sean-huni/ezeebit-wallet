package io.ezeebit.gateway.api.advice;

import io.ezeebit.gateway.exception.EdgeRefusalException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class EdgeRefusalAdvice {

	private final ProblemFactory problems;

	public EdgeRefusalAdvice(final ProblemFactory problems) {
		this.problems = problems;
	}

	@ExceptionHandler(EdgeRefusalException.class)
	ResponseEntity<ProblemDetail> refused(final EdgeRefusalException ex) {
		ProblemDetail problem = problems.of(ex.code());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
		ex.retryAfter().ifPresent(wait -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds(wait.toMillis()))));
		return response.body(problem);
	}

	// Retry-After is whole seconds; rounding down would invite a retry that is refused again.
	static long seconds(final long millis) {
		return Math.max(1, (millis + 999) / 1000);
	}

}
