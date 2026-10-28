package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.UUID;

/**
 * A payment that was sent to the acquiring bank. Only the last four card digits are kept; the full
 * card number and CVV are never part of the stored record.
 */
public record Payment(
    UUID id,
    PaymentStatus status,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount) {

  public static Payment from(UUID id, PaymentCommand command, PaymentStatus status) {
    return new Payment(
        id,
        status,
        command.cardNumberLastFour(),
        command.expiryMonth(),
        command.expiryYear(),
        command.currency(),
        command.amount());
  }
}
