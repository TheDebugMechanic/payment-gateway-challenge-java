package com.checkout.payment.gateway.model;

/**
 * A validated request to charge a card. It carries the full card number and CVV, so it only lives
 * for the duration of a request: it is never stored, and {@link #toString()} masks both.
 */
public record PaymentCommand(
    String cardNumber,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount,
    String cvv) {

  public String cardNumberLastFour() {
    return cardNumber.substring(cardNumber.length() - 4);
  }

  @Override
  public String toString() {
    return "PaymentCommand[cardNumber=****" + cardNumberLastFour()
        + ", expiryMonth=" + expiryMonth
        + ", expiryYear=" + expiryYear
        + ", currency=" + currency
        + ", amount=" + amount
        + ", cvv=***]";
  }
}
