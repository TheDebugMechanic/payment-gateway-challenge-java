package com.checkout.payment.gateway.model;

import com.checkout.payment.gateway.validation.CardExpiry;
import com.checkout.payment.gateway.validation.NotExpired;
import com.checkout.payment.gateway.validation.SupportedCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * A merchant's request to charge a card. Fields are boxed so a missing value reaches validation as
 * {@code null} and is reported as "must not be null" instead of defaulting to zero.
 * Built by {@code PaymentRequestBinder}, which reports wrong-typed fields together with the
 * validation failures.
 *
 * @param cardNumber  the full card number, 14-19 digits
 * @param expiryMonth the card's expiry month, 1-12
 * @param expiryYear  the card's expiry year
 * @param currency    the ISO 4217 currency code: USD, GBP or EUR
 * @param amount      the amount in the currency's minor unit
 * @param cvv         the card verification value, 3-4 digits
 */
@NotExpired
public record PostPaymentRequest(
    @Schema(example = "2222405343248877", description = "14-19 digits")
    @NotBlank @Pattern(regexp = "\\d{14,19}", message = "must be 14-19 digits")
    String cardNumber,

    @Schema(example = "4", description = "1-12")
    @NotNull @Min(1) @Max(12)
    Integer expiryMonth,

    @Schema(example = "2030", description = "Month and year together must not be in the past")
    @NotNull
    Integer expiryYear,

    @Schema(example = "GBP", allowableValues = {"USD", "GBP", "EUR"})
    @NotBlank @SupportedCurrency
    String currency,

    @Schema(example = "1050", description = "Minor currency unit, e.g. 1050 = £10.50")
    @NotNull @Positive
    Long amount,

    @Schema(example = "123", description = "3-4 digits")
    @NotBlank @Pattern(regexp = "\\d{3,4}", message = "must be 3-4 digits")
    String cvv) implements CardExpiry {

  /**
   * Only call on a request that passed validation.
   *
   * @return the command to hand to the payment service
   */
  public PaymentCommand toCommand() {
    return new PaymentCommand(cardNumber, expiryMonth, expiryYear, currency, amount, cvv);
  }

  /**
   * Masks the card number and CVV so the request can be logged.
   *
   * @return a description without the sensitive fields
   */
  @Override
  public String toString() {
    return "PostPaymentRequest[cardNumber=****, expiryMonth=" + expiryMonth
        + ", expiryYear=" + expiryYear + ", currency=" + currency + ", amount=" + amount
        + ", cvv=***]";
  }
}
