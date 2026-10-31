package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.enums.PaymentStatus;
import java.util.UUID;

/**
 * A payment that was sent to the acquiring bank. Only the last four card digits are kept; the full
 * card number and CVV are never part of the stored record.
 *
 * @param id                 the payment's id
 * @param status             the bank's decision
 * @param cardNumberLastFour the last four digits of the card number
 * @param expiryMonth        the card's expiry month, 1-12
 * @param expiryYear         the card's expiry year
 * @param currency           the ISO 4217 currency code
 * @param amount             the amount in the currency's minor unit
 */
public record Payment(
    UUID id,
    PaymentStatus status,
    String cardNumberLastFour,
    int expiryMonth,
    int expiryYear,
    String currency,
    long amount) {

  /**
   * Builds the stored record from a command, dropping the card number and CVV.
   *
   * @param id      the id for the new payment
   * @param command the payment that was sent to the bank
   * @param status  the bank's decision
   * @return the payment to store
   */
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
