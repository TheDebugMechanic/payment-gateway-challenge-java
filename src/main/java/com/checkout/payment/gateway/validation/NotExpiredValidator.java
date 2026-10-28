package com.checkout.payment.gateway.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.YearMonth;

public class NotExpiredValidator implements ConstraintValidator<NotExpired, CardExpiry> {

  @Override
  public boolean isValid(CardExpiry card, ConstraintValidatorContext context) {
    Integer month = card.expiryMonth();
    Integer year = card.expiryYear();
    if (month == null || year == null || month < 1 || month > 12) {
      return true; // missing or out-of-range fields are reported by their own constraints
    }
    YearMonth currentMonth = YearMonth.now(context.getClockProvider().getClock());
    if (!YearMonth.of(year, month).isBefore(currentMonth)) {
      return true;
    }
    // Report against expiry_year so the error points at a field the merchant can fix.
    context.disableDefaultConstraintViolation();
    context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
        .addPropertyNode("expiryYear")
        .addConstraintViolation();
    return false;
  }
}
