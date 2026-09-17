package com.ecommerce.checkout.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous REST client for inventory-service: reserves stock per cart line,
 * then commits or releases every reservation of an order depending on the
 * payment outcome. A declined reserve surfaces as InsufficientStockException
 * so the saga can compensate.
 */
@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(@Value("${ecommerce.inventory.base-url}") String baseUrl,
                           ClientCredentialsTokenProvider tokenProvider) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken);
    }

    public ReservationInfo reserve(Long productId, int quantity, UUID orderId) {
        try {
            return restClient.post()
                    .uri("/internal/api/v1/inventory/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "productId", productId,
                            "quantity", quantity,
                            "orderId", orderId
                    ))
                    .retrieve()
                    .body(ReservationInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }

    public void commitByOrder(UUID orderId) {
        postOrderAction("/internal/api/v1/inventory/reservations/commit-by-order", orderId);
    }

    public void releaseByOrder(UUID orderId) {
        postOrderAction("/internal/api/v1/inventory/reservations/release-by-order", orderId);
    }

    private void postOrderAction(String uri, UUID orderId) {
        try {
            restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
