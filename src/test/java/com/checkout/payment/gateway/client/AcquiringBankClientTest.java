package com.checkout.payment.gateway.client;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.TestPayments.command;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.checkout.payment.gateway.exception.AcquiringBankTimeoutException;
import com.checkout.payment.gateway.exception.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.model.PaymentCommand;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Pins the bank's wire contract and how each kind of bank failure is reported.
 */
@DisplayName("Acquiring bank client")
class AcquiringBankClientTest {

  /**
   * Generous, so a cold first request in the test JVM isn't mistaken for a bank timeout.
   */
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

  @RegisterExtension
  static WireMockExtension bank = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private AcquiringBankClient client;

  /**
   * Builds a client that talks to the WireMock bank.
   */
  @BeforeEach
  void setUp() {
    client = clientFor(URI.create(bank.baseUrl()));
  }

  @DisplayName("Sends the card details in the bank's format")
  @Test
  void sendsTheCardDetailsInTheBanksFormat() {
    bank.stubFor(post("/payments").willReturn(okJson("""
        {"authorized": true, "authorization_code": "0bb07405-6d44-4b50-a14f-7ae0beff13ad"}""")));

    StepVerifier.create(client.authorize(command(AUTHORIZED_CARD)))
        .expectNext(new BankAuthorization(true))
        .verifyComplete();

    bank.verify(postRequestedFor(urlEqualTo("/payments")).withRequestBody(equalToJson("""
        {
          "card_number": "2222405343248877",
          "expiry_date": "04/2099",
          "currency": "GBP",
          "amount": 1050,
          "cvv": "123"
        }""")));
  }

  @DisplayName("Pads single digit months in the expiry date")
  @Test
  void padsSingleDigitMonthsInTheExpiryDate() {
    bank.stubFor(post("/payments").willReturn(okJson("{\"authorized\": true}")));
    PaymentCommand december = new PaymentCommand(AUTHORIZED_CARD, 12, 2030, "USD", 1, "123");
    PaymentCommand january = new PaymentCommand(AUTHORIZED_CARD, 1, 2030, "USD", 1, "123");

    StepVerifier.create(client.authorize(december).then(client.authorize(january)))
        .expectNextCount(1)
        .verifyComplete();

    bank.verify(postRequestedFor(urlEqualTo("/payments"))
        .withRequestBody(equalToJson("{\"expiry_date\": \"12/2030\"}", true, true)));
    bank.verify(postRequestedFor(urlEqualTo("/payments"))
        .withRequestBody(equalToJson("{\"expiry_date\": \"01/2030\"}", true, true)));
  }

  @DisplayName("Reports a decline")
  @Test
  void reportsADecline() {
    bank.stubFor(post("/payments").willReturn(okJson("""
        {"authorized": false, "authorization_code": ""}""")));

    StepVerifier.create(client.authorize(command(AUTHORIZED_CARD)))
        .expectNext(new BankAuthorization(false))
        .verifyComplete();
  }

  @DisplayName("Reports an error response as unavailable and does not retry")
  @ParameterizedTest
  @ValueSource(ints = {400, 500, 503})
  void reportsAnErrorResponseAsUnavailableAndDoesNotRetry(int status) {
    bank.stubFor(post("/payments").willReturn(aResponse().withStatus(status)));

    StepVerifier.create(client.authorize(command(AUTHORIZED_CARD)))
        .verifyError(AcquiringBankUnavailableException.class);

    bank.verify(1, postRequestedFor(urlEqualTo("/payments")));
  }

  @DisplayName("Reports a slow response as a timeout")
  @Test
  void reportsASlowResponseAsATimeout() {
    bank.stubFor(post("/payments").willReturn(okJson("{\"authorized\": true}")
        .withFixedDelay(1_000)));
    AcquiringBankClient impatient = clientFor(URI.create(bank.baseUrl()), Duration.ofMillis(200));

    StepVerifier.create(impatient.authorize(command(AUTHORIZED_CARD)))
        .verifyError(AcquiringBankTimeoutException.class);
  }

  @DisplayName("Reports an unreachable bank as unavailable")
  @Test
  void reportsAnUnreachableBankAsUnavailable() {
    AcquiringBankClient unreachable = clientFor(URI.create("http://localhost:1"));

    StepVerifier.create(unreachable.authorize(command(AUTHORIZED_CARD)))
        .verifyError(AcquiringBankUnavailableException.class);
  }

  /**
   * A client with the default, generous response timeout.
   *
   * @param baseUrl the bank's URL
   * @return the client
   */
  private static AcquiringBankClient clientFor(URI baseUrl) {
    return clientFor(baseUrl, RESPONSE_TIMEOUT);
  }

  /**
   * A client with the given response timeout.
   *
   * @param baseUrl         the bank's URL
   * @param responseTimeout how long to wait for the bank
   * @return the client
   */
  private static AcquiringBankClient clientFor(URI baseUrl, Duration responseTimeout) {
    return new AcquiringBankClient(WebClient.builder(),
        new BankProperties(baseUrl, Duration.ofMillis(500), responseTimeout));
  }
}
