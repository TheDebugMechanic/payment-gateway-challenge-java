package com.checkout.payment.gateway.controller;

import com.checkout.payment.gateway.model.PaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.service.PaymentGatewayService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/v1/payments")
public class PaymentGatewayController implements PaymentGatewayApi {

  private final PaymentGatewayService paymentService;

  public PaymentGatewayController(PaymentGatewayService paymentService) {
    this.paymentService = paymentService;
  }

  @Override
  @PostMapping
  public Mono<ResponseEntity<PaymentResponse>> process(
      @Valid @RequestBody PostPaymentRequest request, UriComponentsBuilder uriBuilder) {
    return paymentService.processPayment(request.toCommand())
        .map(payment -> ResponseEntity
            .created(uriBuilder.path("/v1/payments/{id}").buildAndExpand(payment.id()).toUri())
            .body(PaymentResponse.from(payment)));
  }

  @Override
  @GetMapping("/{id}")
  public Mono<PaymentResponse> get(@PathVariable UUID id) {
    return paymentService.getPaymentById(id).map(PaymentResponse::from);
  }
}
