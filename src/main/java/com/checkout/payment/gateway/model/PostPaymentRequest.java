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
   */
  public PaymentCommand toCommand() {
    return new PaymentCommand(cardNumber, expiryMonth, expiryYear, currency, amount, cvv);
  }

  @Override
  public String toString() {
    return "PostPaymentRequest[cardNumber=****, expiryMonth=" + expiryMonth
        + ", expiryYear=" + expiryYear + ", currency=" + currency + ", amount=" + amount
        + ", cvv=***]";
  }
}
