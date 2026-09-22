package com.ecommerce.payment.client;

import com.ecommerce.common.client.RemoteExceptionMapper;
import com.ecommerce.common.client.RestClients;
import com.ecommerce.common.resilience.ClientResilienceFactory;
import com.ecommerce.common.resilience.Dependencies;
import com.ecommerce.common.security.client.ClientCredentialsTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Synchronous REST client for order-service, authenticated with the
 * service-to-service client-credentials token (SERVICE role). Reads the
 * authoritative order total, currency and status before initiating a payment.
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
