package io.ezeebit.ledger.api.validation;

import java.time.Instant;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SupportedInstantValidator implements ConstraintValidator<SupportedInstant, Instant> {

	static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
	static final Instant END = Instant.parse("+10000-01-01T00:00:00Z");

	@Override
	public boolean isValid(final Instant value, final ConstraintValidatorContext context) {
		return value == null || !value.isBefore(EARLIEST) && value.isBefore(END);
	}

}
