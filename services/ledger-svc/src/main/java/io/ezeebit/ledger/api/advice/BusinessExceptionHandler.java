package io.ezeebit.ledger.api.advice;

import io.ezeebit.ledger.common.LogText;
import io.ezeebit.ledger.exception.LedgerException;
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
	private static final int SERVER_ERROR = 500;

	private final ProblemFactory problems;

	public BusinessExceptionHandler(final ProblemFactory problems) {
		this.problems = problems;
	}

	// A 5xx code here (currency mismatch, unbalanced posting) is an invariant the code broke, not bad input.
	@ExceptionHandler(LedgerException.class)
	ProblemDetail business(final LedgerException ex) {
		if (ex.code().status() >= SERVER_ERROR) {
			LOG.error("ledger invariant violated: {}", LogText.oneLine(ex.getMessage()), ex);
		}
		ProblemDetail problem = problems.of(ex.code(), ex.args());
		if (ex.field() != null) {
			problem.setProperty("field", ex.field());
		}
		return problem;
	}

}
