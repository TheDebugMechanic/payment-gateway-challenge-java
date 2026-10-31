package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.AcquiringBankTimeoutException;
import com.checkout.payment.gateway.exception.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.model.PaymentCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.channel.ChannelOption;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Client for the acquiring bank (here, the bank simulator's {@code POST /payments}).
 *
 * <p>The call is never retried: a payment request is not idempotent, so a retry after a lost
 * response could charge the shopper twice (see docs/adr/0003-no-retries-on-payment-requests.md).
 */
@Component
public class AcquiringBankClient {

  private static final Logger LOG = LoggerFactory.getLogger(AcquiringBankClient.class);

  private final WebClient webClient;
  private final BankProperties properties;

  /**
   * Takes Spring's {@link WebClient.Builder} so outgoing calls get the same metrics and trace
   * propagation as the rest of the application.
   *
   * @param webClientBuilder Spring's builder, which carries the metrics and tracing customizations
   * @param properties       the bank's base URL and timeouts
   */
  public AcquiringBankClient(WebClient.Builder webClientBuilder, BankProperties properties) {
    HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis());
    this.webClient = webClientBuilder
        .baseUrl(properties.baseUrl().toString())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
    this.properties = properties;
  }

  /**
   * Asks the bank to authorize the payment.
   *
   * @param command the validated payment to authorize
   * @return the bank's decision, or an error: {@link AcquiringBankUnavailableException} when the
   * bank did not process the payment, {@link AcquiringBankTimeoutException} when the outcome is
   * unknown
   */
  public Mono<BankAuthorization> authorize(PaymentCommand command) {
    return webClient.post()
        .uri("/payments")
        .bodyValue(BankPaymentRequest.from(command))
        .retrieve()
        .bodyToMono(BankPaymentResponse.class)
        .timeout(properties.responseTimeout())
        .map(response -> new BankAuthorization(response.authorized()))
        .onErrorMap(this::translate);
  }

  /**
   * Maps a low-level failure to the gateway's own exception: a timeout means the outcome is
   * unknown, while an error response or an unreachable bank means nothing was processed.
   *
   * @param error the failure raised by the web client
   * @return the exception to signal downstream, or {@code error} itself when it is not recognised
   */
  private Throwable translate(Throwable error) {
    if (error instanceof TimeoutException) {
      LOG.warn("Acquiring bank did not respond within {}", properties.responseTimeout());
      return new AcquiringBankTimeoutException("Acquiring bank did not respond in time", error);
    }
    if (error instanceof WebClientResponseException response) {
      // A 4xx means we sent something the bank does not accept, which validation should prevent.
      LOG.warn("Acquiring bank returned {}: {}", response.getStatusCode(),
          response.getResponseBodyAsString());
      return new AcquiringBankUnavailableException(
          "Acquiring bank returned " + response.getStatusCode().value(), error);
    }
    if (error instanceof WebClientRequestException) {
      LOG.warn("Could not reach acquiring bank: {}", error.getMessage());
      return new AcquiringBankUnavailableException("Could not reach acquiring bank", error);
    }
    return error;
  }

  /**
   * The bank's wire format. Field names are pinned so they don't follow our API's style.
   */
  record BankPaymentRequest(
      @JsonProperty("card_number") String cardNumber,
      @JsonProperty("expiry_date") String expiryDate,
      @JsonProperty("currency") String currency,
      @JsonProperty("amount") long amount,
      @JsonProperty("cvv") String cvv) {

    /**
     * Converts a payment command to the bank's format, writing the expiry as {@code MM/yyyy}.
     *
     * @param command the validated payment
     * @return the request body to send to the bank
     */
    static BankPaymentRequest from(PaymentCommand command) {
      return new BankPaymentRequest(
          command.cardNumber(),
          "%02d/%d".formatted(command.expiryMonth(), command.expiryYear()),
          command.currency(),
          command.amount(),
          command.cvv());
    }

    /**
     * Masks the card number and CVV so the request can be logged safely.
     *
     * @return a description without the sensitive fields
     */
    @Override
    public String toString() {
      return "BankPaymentRequest[cardNumber=****, expiryDate=" + expiryDate
          + ", currency=" + currency + ", amount=" + amount + ", cvv=***]";
    }
  }

  /**
   * The simulator also returns an {@code authorization_code}; the gateway doesn't use it yet.
   */
  record BankPaymentResponse(@JsonProperty("authorized") boolean authorized) {

  }
}
