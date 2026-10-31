package com.checkout.payment.gateway.exception;

import java.util.List;

/**
 * A payment request has one or more invalid fields. Carries all of them, so the merchant can fix
 * the whole request in one go.
 */
public class InvalidPaymentRequestException extends RuntimeException {

  /** Every invalid field of the request. */
  private final List<FieldError> errors;

  /**
   * Creates the exception.
   *
   * @param errors every invalid field, in no particular order
   */
  public InvalidPaymentRequestException(List<FieldError> errors) {
    super("Payment request has " + errors.size() + " invalid field(s)");
    this.errors = List.copyOf(errors);
  }

  /**
   * The invalid fields.
   *
   * @return every invalid field
   */
  public List<FieldError> errors() {
    return errors;
  }
}
