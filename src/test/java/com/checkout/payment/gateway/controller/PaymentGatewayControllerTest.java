package com.checkout.payment.gateway.controller;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.TestPayments.payment;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.configuration.ApplicationConfiguration;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.AcquiringBankTimeoutException;
import com.checkout.payment.gateway.exception.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.exception.PaymentNotFoundException;
import com.checkout.payment.gateway.model.Payment;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import com.checkout.payment.gateway.service.PaymentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import reactor.core.publisher.Mono;

/**
 * The HTTP contract: status codes, JSON shape and error bodies. The service is mocked.
 */
@WebFluxTest(PaymentGatewayController.class)
@Import({ApplicationConfiguration.class, PaymentMetrics.class,
    PaymentGatewayControllerTest.Observability.class})
class PaymentGatewayControllerTest {

  private static final String VALID_REQUEST = """
      {
        "card_number": "2222405343248877",
        "expiry_month": 4,
        "expiry_year": 2099,
        "currency": "GBP",
        "amount": 1050,
        "cvv": "123"
      }""";

  @Autowired
  private WebTestClient client;

  @MockitoBean
  private PaymentGatewayService paymentService;

  @Nested
  class ProcessPayment {

    @ParameterizedTest
    @ValueSource(strings = {"AUTHORIZED", "DECLINED"})
    void returns201WithTheMaskedPaymentAndItsLocation(PaymentStatus status) {
      Payment payment = payment(status);
      when(paymentService.processPayment(any())).thenReturn(Mono.just(payment));

      post(VALID_REQUEST)
          .expectStatus().isCreated()
          .expectHeader().cacheControl(CacheControl.noStore())
          .expectHeader().location("/v1/payments/" + payment.id())
          .expectBody()
          .jsonPath("$.id").isEqualTo(payment.id().toString())
          .jsonPath("$.status").isEqualTo(status.getName())
          .jsonPath("$.card_number_last_four").isEqualTo("8877")
          .jsonPath("$.expiry_month").isEqualTo(4)
          .jsonPath("$.expiry_year").isEqualTo(2099)
          .jsonPath("$.currency").isEqualTo("GBP")
          .jsonPath("$.amount").isEqualTo(1050)
          .jsonPath("$.card_number").doesNotExist()
          .jsonPath("$.cvv").doesNotExist();
    }

    @Test
    void rejectsInvalidFieldsWithoutCallingTheBank() {
      post("""
          {"card_number": "123", "expiry_month": 13, "expiry_year": 2099,
           "currency": "JPY", "amount": 0}""")
          .expectStatus().isBadRequest()
          .expectHeader().cacheControl(CacheControl.noStore())
          .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.status").isEqualTo(400)
          .jsonPath("$.title").isEqualTo("Payment rejected")
          .jsonPath("$.payment_status").isEqualTo("Rejected")
          .jsonPath("$.errors[*].field")
          .isEqualTo(java.util.List.of("amount", "card_number", "currency", "cvv",
              "expiry_month"));

      verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{not json",
        "",
        "{\"card_number\": \"2222405343248877\", \"expiry_month\": 4, \"expiry_year\": 2099,"
            + " \"currency\": \"GBP\", \"amount\": 10.5, \"cvv\": \"123\"}",
        "{\"card_number\": \"2222405343248877\", \"expiry_month\": \"April\","
            + " \"expiry_year\": 2099, \"currency\": \"GBP\", \"amount\": 1, \"cvv\": \"123\"}"})
    void rejectsABodyThatCannotBeRead(String body) {
      post(body)
          .expectStatus().isBadRequest()
          .expectBody()
          .jsonPath("$.payment_status").isEqualTo("Rejected");

      verifyNoInteractions(paymentService);
    }

    @Test
    void returns502WhenTheBankCannotProcessThePayment() {
      when(paymentService.processPayment(any())).thenReturn(
          Mono.error(new AcquiringBankUnavailableException("503", null)));

      post(VALID_REQUEST)
          .expectStatus().isEqualTo(502)
          .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.title").isEqualTo("Acquiring bank unavailable")
          .jsonPath("$.payment_status").doesNotExist();
    }

    @Test
    void returns504WhenTheBankOutcomeIsUnknown() {
      when(paymentService.processPayment(any())).thenReturn(
          Mono.error(new AcquiringBankTimeoutException("timeout", null)));

      post(VALID_REQUEST)
          .expectStatus().isEqualTo(504)
          .expectBody()
          .jsonPath("$.title").isEqualTo("Acquiring bank timeout");
    }

    @Test
    void neverEchoesTheCardNumberInAnError() {
      post(VALID_REQUEST.replace("\"cvv\": \"123\"", "\"cvv\": \"12\""))
          .expectStatus().isBadRequest()
          .expectBody(String.class)
          .value(body -> org.assertj.core.api.Assertions.assertThat(body)
              .doesNotContain(AUTHORIZED_CARD));
    }

    private ResponseSpec post(String body) {
      return client.post().uri("/v1/payments")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body)
          .exchange();
    }
  }

  @Nested
  class RetrievePayment {

    @Test
    void returnsTheStoredPayment() {
      Payment payment = payment(PaymentStatus.DECLINED);
      when(paymentService.getPaymentById(payment.id())).thenReturn(Mono.just(payment));

      client.get().uri("/v1/payments/{id}", payment.id()).exchange()
          .expectStatus().isOk()
          .expectHeader().cacheControl(CacheControl.noStore())
          .expectBody()
          .jsonPath("$.id").isEqualTo(payment.id().toString())
          .jsonPath("$.status").isEqualTo("Declined")
          .jsonPath("$.card_number_last_four").isEqualTo("8877");
    }

    @Test
    void returns404ForAnUnknownId() {
      UUID id = UUID.randomUUID();
      when(paymentService.getPaymentById(id))
          .thenReturn(Mono.error(new PaymentNotFoundException(id)));

      client.get().uri("/v1/payments/{id}", id).exchange()
          .expectStatus().isNotFound()
          .expectHeader().cacheControl(CacheControl.noStore())
          .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.detail").isEqualTo("Payment " + id + " was not found");
    }

    @Test
    void returns400ForAMalformedId() {
      client.get().uri("/v1/payments/not-a-uuid").exchange()
          .expectStatus().isBadRequest()
          .expectBody()
          .jsonPath("$.payment_status").doesNotExist();

      verifyNoInteractions(paymentService);
    }
  }

  @TestConfiguration
  static class Observability {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    Tracer tracer() {
      return Tracer.NOOP;
    }
  }
}
