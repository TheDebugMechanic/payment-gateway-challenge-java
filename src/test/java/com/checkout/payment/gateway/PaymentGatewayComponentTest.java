package com.checkout.payment.gateway;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.model.PaymentResponse;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The whole application, wired as in production, with WireMock standing in for the bank. Catches
 * wiring and configuration mistakes the slice tests can't, without needing Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureMetrics // Spring Boot tests disable metrics export and tracing unless asked
@AutoConfigureTracing
class PaymentGatewayComponentTest {

  @RegisterExtension
  static WireMockExtension bank = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    registry.add("bank.base-url", bank::baseUrl);
  }

  @Autowired
  private WebTestClient client;

  @Test
  void processesAPaymentThatCanThenBeRetrieved() {
    bank.stubFor(post("/payments").willReturn(okJson("""
        {"authorized": true, "authorization_code": "0bb07405-6d44-4b50-a14f-7ae0beff13ad"}""")));

    PaymentResponse created = client.post().uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"card_number": "%s", "expiry_month": 4, "expiry_year": 2099,
             "currency": "GBP", "amount": 1050, "cvv": "123"}""".formatted(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().exists("X-Trace-Id")
        .expectBody(PaymentResponse.class)
        .returnResult().getResponseBody();

    client.get().uri("/v1/payments/{id}", created.id()).exchange()
        .expectStatus().isOk()
        .expectBody(PaymentResponse.class).isEqualTo(created);
    assertThat(created.cardNumberLastFour()).isEqualTo("8877");
  }

  @Test
  void propagatesTheTraceToTheBank() {
    bank.stubFor(post("/payments").willReturn(okJson("{\"authorized\": false}")));

    String traceId = client.post().uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"card_number": "%s", "expiry_month": 4, "expiry_year": 2099,
             "currency": "GBP", "amount": 1, "cvv": "123"}""".formatted(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isCreated()
        .returnResult(Void.class).getResponseHeaders().getFirst("X-Trace-Id");

    bank.verify(postRequestedFor(urlEqualTo("/payments"))
        .withHeader("traceparent", matching("00-" + traceId + "-.*"))
        .withRequestBody(matchingJsonPath("$.card_number", equalTo(AUTHORIZED_CARD))));
  }

  @Test
  void documentsTheApiWithTheFieldNamesItAccepts() {
    client.get().uri("/v3/api-docs").exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.components.schemas.PostPaymentRequest.properties.card_number").exists()
        .jsonPath("$.components.schemas.PostPaymentRequest.properties.cardNumber").doesNotExist()
        .jsonPath("$.components.schemas.PaymentResponse.properties.card_number_last_four").exists();
  }

  @Test
  void exposesHealthAndPaymentMetrics() {
    client.get().uri("/actuator/health/readiness").exchange()
        .expectStatus().isOk();
    client.get().uri("/actuator/prometheus").exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .value(body -> assertThat(body)
            .contains("payments_processed_total{application=\"payment-gateway\","
                + "status=\"rejected\"}")
            .contains("payments_bank_failures_total")
            .doesNotContain("uri=\"/actuator"));
  }
}
