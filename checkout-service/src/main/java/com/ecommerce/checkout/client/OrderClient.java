package com.ecommerce.checkout.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
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
 */
@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(@Value("${ecommerce.order.base-url}") String baseUrl) {
        this.restClient = RestClients.create(baseUrl);
    }

    public OrderInfo createOrder(OrderCreateRequest request, String idempotencyKey) {
        try {
            var spec = restClient.post()
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

    public OrderInfo markPaid(UUID orderId) {
        return transition(orderId, "/paid");
    }

    public OrderInfo cancel(UUID orderId, String reason) {
        try {
            return restClient.post()
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
            return restClient.post()
                    .uri("/internal/api/v1/orders/{orderId}" + action, orderId)
                    .retrieve()
                    .body(OrderInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
