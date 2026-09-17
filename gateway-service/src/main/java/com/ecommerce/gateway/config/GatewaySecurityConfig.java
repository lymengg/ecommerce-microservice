package com.ecommerce.gateway.config;

import com.ecommerce.gateway.security.KeycloakJwtAuthoritiesConverter;
import com.ecommerce.gateway.security.LazyIssuerReactiveJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Edge security policy (Phase 4): every request that reaches the gateway is
 * either public (product browsing), role-restricted, or denied. The original
 * bearer token is relayed downstream untouched, and each service re-validates
 * it (defense in depth). Internal service-to-service endpoints
 * ({@code /internal/**}) are never reachable through the gateway.
 *
 * <p>Route-to-service wiring lives in {@code application.yml}; role rules here
 * mirror the product surface: product writes and inventory are ADMIN-only,
 * cart/orders/payments/checkout are for customers (and admins, e.g. viewing
 * orders).
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        // CORS preflight must pass before the gateway CORS handler answers it
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .pathMatchers("/api/v1/products/**").hasRole("ADMIN")
                        // provider webhooks carry signatures, not OAuth2 tokens
                        .pathMatchers("/api/v1/payments/webhooks/**").permitAll()
                        .pathMatchers("/api/v1/cart/**", "/api/v1/orders/**", "/api/v1/payments/**", "/api/v1/checkout/**")
                                .hasAnyRole("CUSTOMER", "ADMIN")
                        .pathMatchers("/internal/**").denyAll()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter()))))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, error) -> writeError(
                                exchange.getResponse(), HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required"))
                        .accessDeniedHandler((exchange, error) -> writeError(
                                exchange.getResponse(), HttpStatus.FORBIDDEN, "Forbidden", "Access denied")));
        return http.build();
    }

    /**
     * Decoder bound to the Keycloak issuer: the gateway starts without
     * Keycloak being reachable, and OIDC discovery happens on the first token
     * validation rather than at startup.
     */
    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            return token -> Mono.error(new JwtException("No JWT issuer-uri configured; cannot validate access tokens"));
        }
        return new LazyIssuerReactiveJwtDecoder(issuerUri);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakJwtAuthoritiesConverter());
        return converter;
    }

    private static Mono<Void> writeError(ServerHttpResponse response, HttpStatus status, String title, String detail) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"title\":\"" + title + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
