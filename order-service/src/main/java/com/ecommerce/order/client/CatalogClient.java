package com.ecommerce.order.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Synchronous REST client for catalog-service, authenticated with the
 * service-to-service client-credentials token (SERVICE role). Returns 404 as
 * NotFoundException so an inactive or missing product aborts order creation.
 */
@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(@Value("${ecommerce.catalog.base-url}") String baseUrl,
                         ClientCredentialsTokenProvider tokenProvider,
                         ClientResilienceFactory resilience) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken,
                resilience.forDependency(Dependencies.CATALOG));
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
