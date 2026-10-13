package io.ezeebit.ledger.api.advice;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.RequestHeader;

// Turns framework validation errors into violations whose code is the message key, braces stripped.
@Component
public class ViolationReader {

	public static final String TYPE_MISMATCH = "validation.type.mismatch";

	private final ProblemFactory problems;

	public ViolationReader(final ProblemFactory problems) {
		this.problems = problems;
	}

	public Violation of(final FieldError error) {
		if (error.contains(ConstraintViolation.class)) {
			ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
			return new Violation(error.getField(), key(violation), violation.getMessage());
		}
		return new Violation(error.getField(), TYPE_MISMATCH, problems.message(TYPE_MISMATCH, error.getField()));
	}

	public List<Violation> of(final List<ParameterValidationResult> results) {
		List<Violation> violations = new ArrayList<>();
		for (ParameterValidationResult result : results) {
			if (result instanceof ParameterErrors errors) {
				errors.getFieldErrors().forEach(error -> violations.add(of(error)));
				continue;
			}
			String name = parameterName(result);
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				ConstraintViolation<?> violation = result.unwrap(error, ConstraintViolation.class);
				violations.add(new Violation(name, key(violation), violation.getMessage()));
			}
		}
		return violations;
	}

	public Violation typeMismatch(final String field) {
		return new Violation(field, TYPE_MISMATCH, problems.message(TYPE_MISMATCH, field));
	}

	private static String parameterName(final ParameterValidationResult result) {
		RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
		return header != null ? header.value() : result.getMethodParameter().getParameterName();
	}

	private static String key(final ConstraintViolation<?> violation) {
		return violation.getMessageTemplate().replaceAll("^\\{|\\}$", "");
	}

}
