package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * The payments API's OpenAPI documentation, kept apart from {@link PaymentGatewayController} so the
 * controller reads as plain request handling. Springdoc picks the annotations up from here.
 */
@Tag(name = "Payments")
public interface PaymentGatewayApi {

  /** Media type of error responses (RFC 9457). */
  String PROBLEM_JSON = "application/problem+json";

  /**
   * Processes a card payment.
   *
   * @param body       the merchant's payment request, shaped like {@link PostPaymentRequest}
   * @param uriBuilder builds the {@code Location} header of the created payment
   * @return {@code 201} with the payment, whether the bank authorized or declined it
   */
  @Operation(summary = "Process a card payment",
      description = "Validates the card details and asks the acquiring bank to authorize the "
          + "payment. Invalid requests are rejected without calling the bank.")
  @ApiResponse(responseCode = "201", description = "The bank authorized or declined the payment")
  @ApiResponse(responseCode = "400",
      description = "Rejected: the request failed validation, no payment was created",
      content = @Content(mediaType = PROBLEM_JSON,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "502",
      description = "The bank could not process the payment; no payment was created",
      content = @Content(mediaType = PROBLEM_JSON,
          schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "504",
      description = "The bank did not respond in time; the payment outcome is unknown",
      content = @Content(mediaType = PROBLEM_JSON,
          schema = @Schema(implementation = ProblemDetail.class)))
  Mono<ResponseEntity<PaymentResponse>> process(
      @RequestBody(required = true, content = @Content(
          schema = @Schema(implementation = PostPaymentRequest.class))) JsonNode body,
      @Parameter(hidden = true) UriComponentsBuilder uriBuilder);

  /**
   * Retrieves a previously processed payment.
   *
   * @param id the payment's id
   * @return the payment
   */
  @Operation(summary = "Retrieve a previously processed payment")
  @ApiResponse(responseCode = "200", description = "The payment")
  @ApiResponse(responseCode = "404", description = "No payment has this id",
      content = @Content(mediaType = PROBLEM_JSON,
          schema = @Schema(implementation = ProblemDetail.class)))
  Mono<PaymentResponse> get(UUID id);
}
