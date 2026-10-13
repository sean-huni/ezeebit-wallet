package io.ezeebit.ledger.api.validation;

import java.time.Instant;

import io.ezeebit.ledger.dto.req.StatementReq;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Reported on `to`, so the violation carries a field like every other one.
public class OrderedWindowValidator implements ConstraintValidator<OrderedWindow, StatementReq> {

	@Override
	public boolean isValid(final StatementReq request, final ConstraintValidatorContext context) {
		if (request == null) {
			return true;
		}
		Instant from = request.from();
		Instant to = request.to();
		if (from == null || to == null || from.isBefore(to)) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode("to")
				.addConstraintViolation();
		return false;
	}

}
