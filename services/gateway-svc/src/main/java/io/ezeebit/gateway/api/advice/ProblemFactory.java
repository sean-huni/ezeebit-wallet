package io.ezeebit.gateway.api.advice;

import io.ezeebit.gateway.exception.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

// RFC 9457 body: stable `code` for machines, localised `detail` for people; the same envelope as the services.
@Component
public class ProblemFactory {

	private final MessageSource messages;

	public ProblemFactory(final MessageSource messages) {
		this.messages = messages;
	}

	public ProblemDetail of(final ErrorCode code) {
		String detail = messages.getMessage(code.key(), null, LocaleContextHolder.getLocale());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
		problem.setProperty("code", code.name());
		return problem;
	}

}
