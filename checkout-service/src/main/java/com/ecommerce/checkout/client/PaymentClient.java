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
 * Synchronous REST client for payment-service. Initiation is idempotent via
 * the Idempotency-Key header, so retried checkouts never double-charge.
 */
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${ecommerce.payment.base-url}") String baseUrl,
                         ClientCredentialsTokenProvider tokenProvider) {
        this.restClient = RestClients.createWithServiceToken(baseUrl, tokenProvider::getToken);
    }

    public PaymentInfo initiate(UUID orderId, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/api/v1/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId))
                    .retrieve()
                    .body(PaymentInfo.class);
        } catch (RestClientResponseException ex) {
            throw RemoteExceptionMapper.from(ex);
        }
    }
}
