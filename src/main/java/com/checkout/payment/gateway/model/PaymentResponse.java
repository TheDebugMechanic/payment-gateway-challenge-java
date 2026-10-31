package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A payment as merchants see it. The card number is reduced to its last four digits, kept as a
 * string so leading zeros (e.g. "0042") survive.
 *
 * @param id                 the payment's id
 * @param status             the bank's decision
 * @param cardNumberLastFour the last four digits of the card number
 * @param expiryMonth        the card's expiry month, 1-12
 * @param expiryYear         the card's expiry year
 * @param currency           the ISO 4217 currency code
 * @param amount             the amount in the currency's minor unit
 */
public record PaymentResponse(
    UUID id,
    @Schema(allowableValues = {"Authorized", "Declined"}) PaymentStatus status,
    @Schema(example = "8877") String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount) {

  /**
   * Builds the response for a stored payment.
   *
   * @param payment the stored payment
   * @return the payment as merchants see it
   */
  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.id(),
        payment.status(),
        payment.cardNumberLastFour(),
        payment.expiryMonth(),
        payment.expiryYear(),
        payment.currency(),
        payment.amount());
  }
}
