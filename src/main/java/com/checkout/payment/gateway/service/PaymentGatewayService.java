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

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final AcquiringBankClient bankClient;
  private final PaymentsRepository repository;
  private final PaymentMetrics metrics;

  public PaymentGatewayService(AcquiringBankClient bankClient, PaymentsRepository repository,
      PaymentMetrics metrics) {
    this.bankClient = bankClient;
    this.repository = repository;
    this.metrics = metrics;
  }

  /**
   * Sends a validated payment to the bank and stores the result. If the bank fails, the error is
   * passed on and nothing is stored, because no payment was made (or its outcome is unknown).
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
   */
  public Mono<Payment> getPaymentById(UUID id) {
    return repository.get(id)
        .switchIfEmpty(Mono.error(() -> new PaymentNotFoundException(id)));
  }

  private static PaymentStatus statusOf(BankAuthorization authorization) {
    return authorization.authorized() ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
  }

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
