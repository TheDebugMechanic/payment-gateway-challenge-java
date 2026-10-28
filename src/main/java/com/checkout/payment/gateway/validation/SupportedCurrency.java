package com.checkout.payment.gateway.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * An ISO 4217 code the gateway accepts. The brief limits validation to at most three codes; adding
 * one means changing this pattern and the message.
 */
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@Pattern(regexp = "USD|GBP|EUR")
public @interface SupportedCurrency {

  String message() default "must be one of USD, GBP, EUR";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
