package com.ecommerce.order.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.resilience.Retryable;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for inventory-service's internal endpoints, used by the order
 * reconciliation job: it reads the authoritative reservation state of a stuck
 * order and releases the reservations when the order is compensated (ADR-020).
 */
@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(@Value("${ecommerce.inventory.base-url}") String baseUrl,
                           ClientCredentialsTokenProvider tokenProvider,
                           ClientResilienceFactory resilience) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken,
                resilience.forDependency(Dependencies.INVENTORY));
    }

    /** Authoritative reservation state for an order (GET: retried by default). */
    public List<ReservationInfo> reservationsByOrder(UUID orderId) {
        try {
            return restClient.get()
                    .uri("/internal/api/v1/inventory/reservations?orderId={orderId}", orderId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }

    /**
     * Releases whatever is still RESERVED for the order. Retryable: a repeat is
     * a no-op once nothing is reserved, so a lost response cannot release twice.
     */
    public void releaseByOrder(UUID orderId) {
        try {
            Retryable.yes(restClient.post())
                    .uri("/internal/api/v1/inventory/reservations/release-by-order")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
