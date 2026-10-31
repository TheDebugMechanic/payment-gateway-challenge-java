package com.checkout.payment.gateway.validation;

/**
 * A request that carries a card expiry date, so {@link NotExpired} can check it.
 */
public interface CardExpiry {

  /**
   * The expiry month.
   *
   * @return the month, 1-12, or {@code null} when missing
   */
  Integer expiryMonth();

  /**
   * The expiry year.
   *
   * @return the year, or {@code null} when missing
   */
  Integer expiryYear();
}
