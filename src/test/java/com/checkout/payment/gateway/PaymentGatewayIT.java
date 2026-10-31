package com.checkout.payment.gateway;

import static com.checkout.payment.gateway.TestPayments.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.TestPayments.BANK_ERROR_CARD;
import static com.checkout.payment.gateway.TestPayments.DECLINED_CARD;

import com.checkout.payment.gateway.model.PaymentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * End to end against the real bank simulator (the same Mountebank image and imposters as
 * docker-compose.yml). Needs Docker; runs in {@code ./gradlew check}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("Payment gateway end to end")
class PaymentGatewayIT {

  private static final int BANK_PORT = 8080;

  @Container
  static GenericContainer<?> bankSimulator = new GenericContainer<>("bbyars/mountebank:2.8.1")
      .withCopyFileToContainer(MountableFile.forHostPath("imposters/bank_simulator.ejs"),
          "/imposters/bank_simulator.ejs")
      .withCommand("--configfile", "/imposters/bank_simulator.ejs", "--allowInjection")
      .withExposedPorts(BANK_PORT)
      .waitingFor(Wait.forLogMessage(".*Bank simulator\\] Open for business.*", 1));

  /**
   * Points the gateway at the simulator container's mapped port.
   *
   * @param registry the properties to add to
   */
  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    registry.add("bank.base-url", () -> "http://%s:%d".formatted(
        bankSimulator.getHost(), bankSimulator.getMappedPort(BANK_PORT)));
  }

  @Autowired
  private WebTestClient client;

  @DisplayName("Stores the bank's decision so it can be retrieved")
  @ParameterizedTest(name = "card ending {0} is {1}")
  @CsvSource({AUTHORIZED_CARD + ", Authorized", DECLINED_CARD + ", Declined"})
  void storesTheBanksDecisionSoItCanBeRetrieved(String cardNumber, String expectedStatus) {
    PaymentResponse created = submit(cardNumber)
        .expectStatus().isCreated()
        .expectBody(PaymentResponse.class)
        .returnResult().getResponseBody();

    client.get().uri("/v1/payments/{id}", created.id()).exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo(expectedStatus)
        .jsonPath("$.card_number_last_four").isEqualTo(cardNumber.substring(12));
  }

  @DisplayName("Reports a bank error as bad gateway")
  @Test
  void reportsABankErrorAsBadGateway() {
    submit(BANK_ERROR_CARD)
        .expectStatus().isEqualTo(502)
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @DisplayName("Rejects an invalid payment")
  @Test
  void rejectsAnInvalidPayment() {
    client.post().uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"card_number\": \"%s\"}".formatted(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.payment_status").isEqualTo("Rejected");
  }

  /**
   * Posts a valid payment for the given card.
   *
   * @param cardNumber the card to charge
   * @return the gateway's response
   */
  private WebTestClient.ResponseSpec submit(String cardNumber) {
    return client.post().uri("/v1/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {"card_number": "%s", "expiry_month": 4, "expiry_year": 2099,
             "currency": "EUR", "amount": 1050, "cvv": "123"}""".formatted(cardNumber))
        .exchange();
  }
}
