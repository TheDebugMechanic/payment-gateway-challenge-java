package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import com.checkout.payment.gateway.validation.PaymentRequestBinder;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * HTTP endpoints for processing and retrieving payments.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentGatewayController implements PaymentGatewayApi {

  private final PaymentGatewayService paymentService;
  private final PaymentRequestBinder binder;

  /**
   * Creates the controller.
   *
   * @param paymentService the service that runs the payment flow
   * @param binder         reads and validates the request body
   */
  public PaymentGatewayController(PaymentGatewayService paymentService,
      PaymentRequestBinder binder) {
    this.paymentService = paymentService;
    this.binder = binder;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @PostMapping
  public Mono<ResponseEntity<PaymentResponse>> process(
      @RequestBody JsonNode body, UriComponentsBuilder uriBuilder) {
    return paymentService.processPayment(binder.bind(body).toCommand())
        .map(payment -> ResponseEntity
            .created(uriBuilder.path("/v1/payments/{id}").buildAndExpand(payment.id()).toUri())
            .body(PaymentResponse.from(payment)));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @GetMapping("/{id}")
  public Mono<PaymentResponse> get(@PathVariable UUID id) {
    return paymentService.getPaymentById(id).map(PaymentResponse::from);
  }
}
