package io.ezeebit.ledger.api.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// An instant Postgres timestamptz can hold and a statement can meaningfully ask for.
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SupportedInstantValidator.class)
public @interface SupportedInstant {

	String message() default "{validation.instant.range}";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
