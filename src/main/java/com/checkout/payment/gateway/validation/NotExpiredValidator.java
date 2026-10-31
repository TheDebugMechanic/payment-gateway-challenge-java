package com.checkout.payment.gateway.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.YearMonth;

/**
 * Checks {@link NotExpired}. A card is valid until the end of its expiry month.
 */
public class NotExpiredValidator implements ConstraintValidator<NotExpired, CardExpiry> {

  /**
   * Reports an expired card against the {@code expiryYear} field. Missing or out-of-range values
   * are left to the field-level constraints, so they are not reported twice.
   *
   * @param card    the request to check
   * @param context the validation context
   * @return {@code false} only when the expiry month is in the past
   */
  @Override
  public boolean isValid(CardExpiry card, ConstraintValidatorContext context) {
    Integer month = card.expiryMonth();
    Integer year = card.expiryYear();
    if (month == null || year == null || month < 1 || month > 12) {
      return true;
    }
    YearMonth currentMonth = YearMonth.now(context.getClockProvider().getClock());
    if (!YearMonth.of(year, month).isBefore(currentMonth)) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
        .addPropertyNode("expiryYear")
        .addConstraintViolation();
    return false;
  }
}
