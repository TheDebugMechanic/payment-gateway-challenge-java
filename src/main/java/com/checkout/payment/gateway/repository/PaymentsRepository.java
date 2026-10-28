package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.model.Payment;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Test-double storage allowed by the brief. Data is lost on restart and is not shared between
 * instances; see docs/adr/0002-in-memory-storage.md. Methods return {@link Mono} so a reactive
 * database client can replace the map without changing callers.
 */
@Repository
public class PaymentsRepository {

  private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();

  public Mono<Payment> add(Payment payment) {
    return Mono.fromSupplier(() -> {
      payments.put(payment.id(), payment);
      return payment;
    });
  }

  /**
   * Emits the payment, or completes empty when no payment has that id.
   */
  public Mono<Payment> get(UUID id) {
    return Mono.fromSupplier(() -> payments.get(id));
  }
}
