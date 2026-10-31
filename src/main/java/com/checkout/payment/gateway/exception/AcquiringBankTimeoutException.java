package com.checkout.payment.gateway.exception;

/**
 * The bank did not answer in time. Unlike {@link AcquiringBankUnavailableException}, the bank may
 * still have authorized the payment, so the outcome is unknown.
 */
public class AcquiringBankTimeoutException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause   the underlying timeout
   */
  public AcquiringBankTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
