package com.checkout.payment.gateway.repository;

import static com.checkout.payment.gateway.TestPayments.payment;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class PaymentsRepositoryTest {

  private final PaymentsRepository repository = new PaymentsRepository();

  @Test
  void findsASavedPayment() {
    Payment payment = payment(PaymentStatus.AUTHORIZED);

    StepVerifier.create(repository.add(payment).then(repository.get(payment.id())))
        .expectNext(payment)
        .verifyComplete();
  }

  @Test
  void completesEmptyForAnUnknownId() {
    StepVerifier.create(repository.get(UUID.randomUUID())).verifyComplete();
  }

  @Test
  void savesNothingUntilSubscribed() {
    Payment payment = payment(PaymentStatus.AUTHORIZED);

    repository.add(payment); // assembled but never subscribed

    StepVerifier.create(repository.get(payment.id())).verifyComplete();
  }
}
