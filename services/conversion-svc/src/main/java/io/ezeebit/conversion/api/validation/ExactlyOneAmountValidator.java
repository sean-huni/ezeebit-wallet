package io.ezeebit.conversion.api.validation;

import io.ezeebit.conversion.dto.req.QuoteReq;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Reported on sellAmount, so the violation carries a field like every other one.
public class ExactlyOneAmountValidator implements ConstraintValidator<ExactlyOneAmount, QuoteReq> {

	@Override
	public boolean isValid(final QuoteReq request, final ConstraintValidatorContext context) {
		if (request == null || (request.sellAmount() == null) != (request.buyAmount() == null)) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode("sellAmount")
				.addConstraintViolation();
		return false;
	}

}
