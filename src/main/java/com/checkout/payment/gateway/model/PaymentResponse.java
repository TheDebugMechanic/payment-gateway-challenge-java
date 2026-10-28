package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A payment as merchants see it. The card number is reduced to its last four digits, kept as a
 * string so leading zeros (e.g. "0042") survive.
 */
public record PaymentResponse(
    UUID id,
    @Schema(allowableValues = {"Authorized", "Declined"}) PaymentStatus status,
    @Schema(example = "8877") String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount) {

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
