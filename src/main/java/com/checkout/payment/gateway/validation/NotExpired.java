package com.checkout.payment.gateway.validation;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The card's expiry month and year, taken together, must not be in the past. A card stays valid
 * until the end of its expiry month. The current month comes from the validator's
 * {@link jakarta.validation.ClockProvider}, so tests can fix the date.
 */
@Target(TYPE)
@Retention(RUNTIME)
@Constraint(validatedBy = NotExpiredValidator.class)
public @interface NotExpired {

  /**
   * The message reported when the card has expired.
   *
   * @return the message template
   */
  String message() default "card has expired";

  /**
   * The validation groups this constraint belongs to.
   *
   * @return the groups
   */
  Class<?>[] groups() default {};

  /**
   * Extra metadata for clients of the validation API.
   *
   * @return the payload types
   */
  Class<? extends Payload>[] payload() default {};
}
