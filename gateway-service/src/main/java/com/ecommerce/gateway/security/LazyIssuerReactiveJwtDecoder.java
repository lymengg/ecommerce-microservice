package com.ecommerce.gateway.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of the lazy decoder in the {@code common} module (the
 * gateway does not depend on {@code common} because that would pull in the
 * servlet web stack — keep the two in sync): OIDC discovery happens on the
 * first token validation, so the gateway starts without Keycloak being
 * reachable.
 */
public final class LazyIssuerReactiveJwtDecoder implements ReactiveJwtDecoder {

    private final String issuerUri;
    private volatile NimbusReactiveJwtDecoder delegate;

    public LazyIssuerReactiveJwtDecoder(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public Mono<Jwt> decode(String token) throws JwtException {
        return Mono.fromSupplier(this::delegate).flatMap(decoder -> decoder.decode(token));
    }

    private NimbusReactiveJwtDecoder delegate() {
        NimbusReactiveJwtDecoder current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                delegate = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
            }
            return delegate;
        }
    }
}
