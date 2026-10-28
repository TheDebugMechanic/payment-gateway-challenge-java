package com.checkout.payment.gateway.exception;

/**
 * The bank answered with an error or could not be reached, so the payment was not processed.
 */
public class AcquiringBankUnavailableException extends RuntimeException {

  public AcquiringBankUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
