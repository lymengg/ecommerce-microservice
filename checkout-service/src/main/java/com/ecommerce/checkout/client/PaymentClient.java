package com.ecommerce.checkout.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.resilience.Retryable;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous REST client for payment-service. Initiation is idempotent via
 * the Idempotency-Key header, so retried checkouts never double-charge — which
 * is exactly what makes it safe to opt into retries here (ADR-019).
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

    public PaymentInfo initiate(UUID orderId, String idempotencyKey) {
        try {
            return Retryable.yes(restClient.post())
                    .uri("/api/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId))
                    .retrieve()
                    .body(PaymentInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
