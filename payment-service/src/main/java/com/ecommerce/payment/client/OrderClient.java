package com.ecommerce.payment.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Synchronous REST client for order-service. Reads the authoritative order
 * total, currency and status before initiating a payment.
 */
@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(@Value("${ecommerce.order.base-url}") String baseUrl) {
        this.restClient = RestClients.create(baseUrl);
    }

    public OrderInfo getOrder(UUID orderId) {
        try {
            return restClient.get()
                    .uri("/api/v1/orders/{orderId}", orderId)
                    .retrieve()
                    .body(OrderInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
