package com.ecommerce.cart.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Synchronous REST client for catalog-service. Returns 404 as
 * NotFoundException so inactive products are indistinguishable from missing
 * ones (the cart displays them as unavailable).
 */
@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(@Value("${ecommerce.catalog.base-url}") String baseUrl) {
        this.restClient = RestClients.create(baseUrl);
    }

    public CatalogProduct getActiveProduct(Long productId) {
        try {
            return restClient.get()
                    .uri("/internal/api/v1/catalog/products/{id}", productId)
                    .retrieve()
                    .body(CatalogProduct.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
