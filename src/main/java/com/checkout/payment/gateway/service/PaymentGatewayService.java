package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.AcquiringBankClient;
import com.checkout.payment.gateway.client.BankAuthorization;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PaymentCommand;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Runs the payment flow: authorize with the bank, store the result and record metrics.
 */
@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final AcquiringBankClient bankClient;
  private final PaymentsRepository repository;
  private final PaymentMetrics metrics;

  /**
   * Creates the service.
   *
   * @param bankClient the acquiring bank client
   * @param repository where payments are stored
   * @param metrics    records payment outcomes and bank failures
   */
  public PaymentGatewayService(AcquiringBankClient bankClient, PaymentsRepository repository,
      PaymentMetrics metrics) {
    this.bankClient = bankClient;
    this.repository = repository;
    this.metrics = metrics;
  }

  /**
   * Sends a validated payment to the bank and stores the result. If the bank fails, the error is
   * passed on and nothing is stored, because no payment was made (or its outcome is unknown).
   *
   * @param command the validated payment
   * @return the stored payment, or an error when the bank failed
   */
  public Mono<Payment> processPayment(PaymentCommand command) {
    return bankClient.authorize(command)
        .doOnError(error -> metrics.recordBankFailure())
        .flatMap(authorization -> repository.add(
            Payment.from(UUID.randomUUID(), command, statusOf(authorization))))
        .doOnNext(this::recordOutcome);
  }

  /**
   * Emits the payment, or a {@link PaymentNotFoundException} when no payment has that id.
   *
   * @param id the payment's id
   * @return the payment, or a not-found error
   */
  public Mono<Payment> getPaymentById(UUID id) {
    return repository.get(id)
        .switchIfEmpty(Mono.error(() -> new PaymentNotFoundException(id)));
  }

  /**
   * Maps the bank's decision to a payment status.
   *
   * @param authorization the bank's decision
   * @return {@link PaymentStatus#AUTHORIZED} or {@link PaymentStatus#DECLINED}
   */
  private static PaymentStatus statusOf(BankAuthorization authorization) {
    return authorization.authorized() ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
  }

  /**
   * Counts the outcome and logs it, with only the last four card digits.
   *
   * @param payment the stored payment
   */
  private void recordOutcome(Payment payment) {
    metrics.recordOutcome(payment.status());
    LOG.atInfo()
        .addKeyValue("payment_id", payment.id())
        .addKeyValue("payment_status", payment.status().getName())
        .addKeyValue("amount", payment.amount())
        .addKeyValue("currency", payment.currency())
        .addKeyValue("card_last_four", payment.cardNumberLastFour())
        .log("Payment processed");
  }
}
