package com.checkout.payment.gateway.service;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.TestPayments.command;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.AcquiringBankClient;
import com.checkout.payment.gateway.client.BankAuthorization;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankTimeoutException;
import com.checkout.payment.gateway.exception.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.model.PaymentCommand;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The payment flow with the bank mocked: what is stored, counted and passed on.
 */
@DisplayName("Payment gateway service")
class PaymentGatewayServiceTest {

  private final AcquiringBankClient bank = mock(AcquiringBankClient.class);
  private final PaymentsRepository repository = spy(new PaymentsRepository());
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private PaymentGatewayService service;

  /**
   * Builds the service with the mocked bank and a fresh registry.
   */
  @BeforeEach
  void setUp() {
    service = new PaymentGatewayService(bank, repository, new PaymentMetrics(registry));
  }

  @DisplayName("Stores an authorized payment with only the last four card digits")
  @Test
  void storesAnAuthorizedPaymentWithOnlyTheLastFourCardDigits() {
    PaymentCommand command = command(AUTHORIZED_CARD);
    when(bank.authorize(command)).thenReturn(Mono.just(new BankAuthorization(true)));

    Payment payment = service.processPayment(command).block();

    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment).usingRecursiveComparison().ignoringFields("id", "status")
        .isEqualTo(new Payment(null, null, "8877", 4, 2099, "GBP", 1050L));
    assertThat(payment.toString()).doesNotContain(AUTHORIZED_CARD);
    StepVerifier.create(service.getPaymentById(payment.id())).expectNext(payment).verifyComplete();
    assertThat(processedCount("authorized")).isEqualTo(1);
  }

  @DisplayName("Stores a declined payment")
  @Test
  void storesADeclinedPayment() {
    when(bank.authorize(any())).thenReturn(Mono.just(new BankAuthorization(false)));

    StepVerifier.create(service.processPayment(command(AUTHORIZED_CARD)))
        .assertNext(payment -> assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED))
        .verifyComplete();
    assertThat(processedCount("declined")).isEqualTo(1);
  }

  @DisplayName("Passes on bank failures without storing a payment")
  @ParameterizedTest
  @ValueSource(classes = {AcquiringBankUnavailableException.class,
      AcquiringBankTimeoutException.class})
  void passesOnBankFailuresWithoutStoringAPayment(Class<? extends RuntimeException> failure)
      throws Exception {
    RuntimeException error = failure.getConstructor(String.class, Throwable.class)
        .newInstance("bank failed", null);
    when(bank.authorize(any())).thenReturn(Mono.error(error));

    StepVerifier.create(service.processPayment(command(AUTHORIZED_CARD))).verifyErrorMatches(
        e -> e == error);

    verify(repository, never()).add(any());
    assertThat(registry.get("payments.bank.failures").counter().count()).isEqualTo(1);
  }

  @DisplayName("Signals not found for an unknown id")
  @Test
  void signalsNotFoundForAnUnknownId() {
    StepVerifier.create(service.getPaymentById(UUID.randomUUID()))
        .verifyError(PaymentNotFoundException.class);
  }

  /**
   * Reads the processed-payments counter for a status.
   *
   * @param status the status tag, in lower case
   * @return the current count
   */
  private double processedCount(String status) {
    return registry.get("payments.processed").tag("status", status).counter().count();
  }
}
