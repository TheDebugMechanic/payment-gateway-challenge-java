package com.checkout.payment.gateway.repository;

import static com.checkout.payment.gateway.TestPayments.payment;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.Payment;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Storing and finding payments in memory.
 */
@DisplayName("Payments repository")
class PaymentsRepositoryTest {

  private final PaymentsRepository repository = new PaymentsRepository();

  @DisplayName("Finds a saved payment")
  @Test
  void findsASavedPayment() {
    Payment payment = payment(PaymentStatus.AUTHORIZED);

    StepVerifier.create(repository.add(payment).then(repository.get(payment.id())))
        .expectNext(payment)
        .verifyComplete();
  }

  @DisplayName("Completes empty for an unknown id")
  @Test
  void completesEmptyForAnUnknownId() {
    StepVerifier.create(repository.get(UUID.randomUUID())).verifyComplete();
  }

  @DisplayName("Saves nothing until subscribed")
  @Test
  void savesNothingUntilSubscribed() {
    Payment payment = payment(PaymentStatus.AUTHORIZED);

    repository.add(payment); // assembled but never subscribed

    StepVerifier.create(repository.get(payment.id())).verifyComplete();
  }
}
