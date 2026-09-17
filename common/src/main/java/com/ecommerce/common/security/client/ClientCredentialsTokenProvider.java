package com.ecommerce.common.security.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Fetches service-to-service access tokens from Keycloak using the OAuth2
 * client-credentials grant and caches them until shortly before expiry.
 * Activated only when {@code ecommerce.security.service-client.enabled=true},
 * i.e. in services that call other services' protected endpoints (checkout,
 * cart, order, payment).
 *
 * <p>The returned token carries the SERVICE realm role; every outbound call
 * made with it is attributed to the platform rather than to an end user, and
 * downstream services enforce object-level authorization themselves.
 */
@Component
@ConditionalOnProperty(prefix = "ecommerce.security.service-client", name = "enabled", havingValue = "true")
public class ClientCredentialsTokenProvider {

    /** Never fetch a new token if the cached one has more than this much life left. */
    private static final long REFRESH_SKEW_SECONDS = 30;

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    private volatile CachedToken cached;

    public ClientCredentialsTokenProvider(
            @Value("${ecommerce.security.service-client.token-uri}") String tokenUri,
            @Value("${ecommerce.security.service-client.client-id}") String clientId,
            @Value("${ecommerce.security.service-client.client-secret}") String clientSecret) {
        this.restClient = RestClient.builder()
                .baseUrl(tokenUri)
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String getToken() {
        CachedToken current = cached;
        if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(REFRESH_SKEW_SECONDS))) {
            return current.token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        String token = (String) response.get("access_token");
        if (token == null) {
            throw new IllegalStateException("Keycloak client-credentials response did not contain an access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 300L;
        cached = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        return token;
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}
