package io.ezeebit.ledger.api.advice;

import java.util.Arrays;
import java.util.List;

import io.ezeebit.ledger.exception.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

// RFC 9457 body: stable `code` for machines, localised `detail` for people.
@Component
public class ProblemFactory {

	private static final int MAX_ECHO = 64;

	private final MessageSource messages;

	public ProblemFactory(final MessageSource messages) {
		this.messages = messages;
	}

	public ProblemDetail of(final ErrorCode code, final Object... args) {
		Object[] safe = Arrays.stream(args).map(ProblemFactory::scrub).toArray();
		String detail = messages.getMessage(code.key(), safe, LocaleContextHolder.getLocale());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
		problem.setProperty("code", code.name());
		return problem;
	}

	public ProblemDetail invalid(final List<Violation> violations) {
		ProblemDetail problem = of(ErrorCode.VALIDATION_FAILED);
		problem.setProperty("errors", violations);
		return problem;
	}

	public String message(final String key, final Object... args) {
		return messages.getMessage(key, args, LocaleContextHolder.getLocale());
	}

	// Request values echoed into a response lose control characters and are capped in length.
	private static Object scrub(final Object arg) {
		if (!(arg instanceof String text)) {
			return arg;
		}
		String clean = text.replaceAll("\\p{Cntrl}", "");
		return clean.length() > MAX_ECHO ? clean.substring(0, MAX_ECHO) : clean;
	}

}
