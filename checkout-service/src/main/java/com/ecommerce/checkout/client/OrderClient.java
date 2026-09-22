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
 * Synchronous REST client for order-service: creates the order and advances
 * its state machine as the saga progresses. Order creation is idempotent via
 * the Idempotency-Key header.
 *
 * <p>Marking an order PAID is no longer done here: since Phase 6c that is
 * driven by the {@code PaymentSucceeded} event consumed by order-service.
 *
 * <p>Only creation is retried. The state transitions are not: a transition
 * repeated after a lost response is rejected as an invalid transition (409)
 * rather than absorbed, so retrying it would convert a slow call into a
 * failure. The saga's compensation handles a transition that never landed
 * (ADR-019).
 */
@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(@Value("${ecommerce.order.base-url}") String baseUrl,
                       ClientCredentialsTokenProvider tokenProvider,
                       ClientResilienceFactory resilience) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken,
                resilience.forDependency(Dependencies.ORDER));
    }

    public OrderInfo createOrder(OrderCreateRequest request, String idempotencyKey) {
        try {
            var spec = Retryable.yes(restClient.post())
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request);
            if (idempotencyKey != null) {
                spec = spec.header("Idempotency-Key", idempotencyKey);
            }
            return spec.retrieve().body(OrderInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }

    public OrderInfo markPending(UUID orderId) {
        return transition(orderId, "/pending");
    }

    public OrderInfo markPaymentPending(UUID orderId) {
        return transition(orderId, "/payment-pending");
    }

    public OrderInfo cancel(UUID orderId, String reason) {
        try {
            return Retryable.no(restClient.post())
                    .uri("/api/v1/orders/{orderId}/cancel", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(OrderInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }

    private OrderInfo transition(UUID orderId, String action) {
        try {
            return Retryable.no(restClient.post())
                    .uri("/internal/api/v1/orders/{orderId}" + action, orderId)
                    .retrieve()
                    .body(OrderInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
