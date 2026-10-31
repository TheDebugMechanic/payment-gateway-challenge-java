package com.checkout.payment.gateway.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The outcome of a payment request.
 */
public enum PaymentStatus {
  /** The bank approved the payment. */
  AUTHORIZED("Authorized"),
  /** The bank refused the payment. */
  DECLINED("Declined"),
  /** The request was invalid, so the bank was never called. */
  REJECTED("Rejected");

  private final String name;

  /**
   * Creates a status.
   *
   * @param name the name used in API responses
   */
  PaymentStatus(String name) {
    this.name = name;
  }

  /**
   * The name used in API responses, such as {@code Authorized}.
   *
   * @return the display name
   */
  @JsonValue
  public String getName() {
    return this.name;
  }
}
