package com.checkout.payment.gateway.validation;

/**
 * A request that carries a card expiry date, so {@link NotExpired} can check it.
 */
public interface CardExpiry {

  Integer expiryMonth();

  Integer expiryYear();
}
