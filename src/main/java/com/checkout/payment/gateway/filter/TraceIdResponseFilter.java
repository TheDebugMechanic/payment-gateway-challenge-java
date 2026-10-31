package com.checkout.payment.gateway.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Returns the trace id in an {@code X-Trace-Id} header, so a merchant reporting a problem can hand
 * us the id that finds the request's logs and trace.
 */
@Component
public class TraceIdResponseFilter implements WebFilter {

  static final String TRACE_ID_HEADER = "X-Trace-Id";

  private final Tracer tracer;

  /**
   * Creates the filter.
   *
   * @param tracer the tracer that knows the current span
   */
  public TraceIdResponseFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  /**
   * Adds the current trace id to the response headers, when there is a trace.
   *
   * @param exchange the current exchange
   * @param chain    the rest of the filter chain
   * @return completes when the rest of the chain has completed
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    Span span = tracer.currentSpan();
    if (span != null) {
      exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, span.context().traceId());
    }
    return chain.filter(exchange);
  }
}
