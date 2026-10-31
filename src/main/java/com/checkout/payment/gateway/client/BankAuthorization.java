package com.checkout.payment.gateway.client;

/**
 * The bank's decision on a payment.
 *
 * @param authorized {@code true} when the bank authorized the payment, {@code false} when it
 *                   declined it
 */
public record BankAuthorization(boolean authorized) {

}
