package com.ecommerce.checkout.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.resilience.Retryable;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
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

    public CartClient(@Value("${ecommerce.cart.base-url}") String baseUrl,
                      ClientCredentialsTokenProvider tokenProvider,
                      ClientResilienceFactory resilience) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken,
                resilience.forDependency(Dependencies.CART));
    }

    public List<CartLineInfo> getLines(UUID cartId) {
        try {
            // GET: retryable by default (a read has no side effect).
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
            // Not retried: the cart transition is a state change, and a repeat
            // after a lost response would be answered 409 rather than being a
            // harmless no-op (ADR-019).
            Retryable.no(restClient.post())
                    .uri("/internal/api/v1/cart/{cartId}/checkout", cartId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
