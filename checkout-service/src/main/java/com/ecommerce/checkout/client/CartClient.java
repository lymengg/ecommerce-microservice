package com.ecommerce.checkout.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;

/**
 * Synchronous REST client for cart-service: reads the checkout lines and
 * closes the cart once the saga succeeds.
 */
@Component
public class CartClient {

    private final RestClient restClient;

    public CartClient(@Value("${ecommerce.cart.base-url}") String baseUrl) {
        this.restClient = RestClients.create(baseUrl);
    }

    public List<CartLineInfo> getLines(UUID cartId) {
        try {
            return restClient.get()
                    .uri("/internal/api/v1/cart/{cartId}/lines", cartId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }

    public void markCheckedOut(UUID cartId) {
        try {
            restClient.post()
                    .uri("/internal/api/v1/cart/{cartId}/checkout", cartId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
