package com.checkout.payment.gateway.model;

/**
 * A validated request to charge a card. It carries the full card number and CVV, so it only lives
 * for the duration of a request: it is never stored, and {@link #toString()} masks both.
 *
 * @param cardNumber  the full card number
 * @param expiryMonth the card's expiry month, 1-12
 * @param expiryYear  the card's expiry year
 * @param currency    the ISO 4217 currency code
 * @param amount      the amount in the currency's minor unit
 * @param cvv         the card verification value
 */
public record PaymentCommand(
    String cardNumber,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount,
    String cvv) {

  /**
   * The last four digits of the card number.
   *
   * @return the last four digits
   */
  public String cardNumberLastFour() {
    return cardNumber.substring(cardNumber.length() - 4);
  }

  /**
   * Masks the card number (except its last four digits) and the CVV so the command can be logged.
   *
   * @return a description without the sensitive fields
   */
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
