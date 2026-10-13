package io.ezeebit.conversion.api.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A quote fixes one side: either what the merchant sells or what it buys, never both, never neither.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExactlyOneAmountValidator.class)
public @interface ExactlyOneAmount {

	String message() default "{validation.amount.exactlyOne}";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
