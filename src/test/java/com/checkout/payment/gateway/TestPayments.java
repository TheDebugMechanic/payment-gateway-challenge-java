package com.checkout.payment.gateway;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PaymentCommand;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import java.util.UUID;

/**
 * Valid sample data. Tests change only the fields they are about.
 */
public final class TestPayments {

  /**
   * Ends in an odd digit, so the bank simulator authorizes it.
   */
  public static final String AUTHORIZED_CARD = "2222405343248877";
  /**
   * Ends in an even digit, so the bank simulator declines it.
   */
  public static final String DECLINED_CARD = "2222405343248112";
  /**
   * Ends in zero, so the bank simulator answers 503.
   */
  public static final String BANK_ERROR_CARD = "2222405343248870";

  private TestPayments() {
  }

  public static PostPaymentRequest request() {
    return new PostPaymentRequest(AUTHORIZED_CARD, 4, 2099, "GBP", 1050L, "123");
  }

  public static PaymentCommand command(String cardNumber) {
    return new PaymentCommand(cardNumber, 4, 2099, "GBP", 1050L, "123");
  }

  public static Payment payment(PaymentStatus status) {
    return Payment.from(UUID.randomUUID(), command(AUTHORIZED_CARD), status);
  }
}
