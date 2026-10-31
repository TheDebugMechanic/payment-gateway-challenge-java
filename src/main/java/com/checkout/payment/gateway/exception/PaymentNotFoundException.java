package com.checkout.payment.gateway.exception;

import java.util.UUID;

/**
 * No payment exists with the requested id.
 */
public class PaymentNotFoundException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param id the id that was not found
   */
  public PaymentNotFoundException(UUID id) {
    super("Payment " + id + " was not found");
  }
}
