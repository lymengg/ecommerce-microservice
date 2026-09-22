package com.ecommerce.order.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.error.NotFoundException;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Read-only client for payment-service's internal endpoint, used by the order
 * reconciliation job to learn the authoritative payment state of a stuck order
 * (ADR-020). Reconciliation asks the owning service; it never reads payment's
 * tables (ADR-003).
 *
 * <p>Added in Phase 7 — order-service previously had no reason to call
 * payment-service at all.
 */
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${ecommerce.payment.base-url}") String baseUrl,
                         ClientCredentialsTokenProvider tokenProvider,
                         ClientResilienceFactory resilience) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken,
                resilience.forDependency(Dependencies.PAYMENT));
    }

    /**
     * The payment for an order, or {@code null} when payment-service has none.
     * "No payment exists" is a normal reconciliation outcome (the saga failed
     * before reaching payment), not an error, so a 404 is not an exception here.
     */
    public PaymentInfo findByOrder(UUID orderId) {
        try {
            return restClient.get()
                    .uri("/internal/api/v1/payments/by-order/{orderId}", orderId)
                    .retrieve()
                    .body(PaymentInfo.class);
        } catch (RestClientResponseException ex) {
            // A 404 is "payment-service has no payment for this order", which is
            // a normal reconciliation outcome. Note it is the *mapped* exception
            // that carries that meaning: the raw one is an
            // HttpClientErrorException, so the check has to happen after
            // mapping, not as a catch clause on NotFoundException.
            RuntimeException mapped = RemoteExceptionMapper.from(ex);
            if (mapped instanceof NotFoundException) {
                return null;
            }
            throw mapped;
        }
    }
}
