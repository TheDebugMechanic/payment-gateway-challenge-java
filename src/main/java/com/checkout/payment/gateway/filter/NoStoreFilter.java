package com.checkout.payment.gateway.filter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Tells browsers, proxies and CDNs not to keep payment responses. They carry payment details, and
 * nothing else in the stack (no Spring Security here) sets this header for us.
 */
@Component
public class NoStoreFilter implements WebFilter {

  private static final String PAYMENTS_PATH = "/v1/payments";

  /**
   * Adds {@code Cache-Control: no-store} to responses under {@code /v1/payments}.
   *
   * @param exchange the current exchange
   * @param chain    the rest of the filter chain
   * @return completes when the rest of the chain has completed
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (exchange.getRequest().getPath().value().startsWith(PAYMENTS_PATH)) {
      exchange.getResponse().getHeaders()
          .set(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }
    return chain.filter(exchange);
  }
}
