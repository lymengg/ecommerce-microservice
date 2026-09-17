package com.ecommerce.common.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JwtDecoder that performs OIDC discovery from the issuer URI lazily — on the
 * first token validation — instead of at bean creation. Services therefore
 * start (and their tests run) without Keycloak being reachable; discovery
 * happens only when a real access token arrives.
 *
 * <p>Needed because {@code NimbusJwtDecoder.withIssuerLocation(...).build()}
 * resolves the OIDC configuration eagerly, and the servlet
 * {@code WebSecurityConfiguration} instantiates the decoder at startup
 * regardless of {@code @Lazy} on the bean.
 */
public final class LazyIssuerJwtDecoder implements JwtDecoder {

    private final String issuerUri;
    private volatile NimbusJwtDecoder delegate;

    public LazyIssuerJwtDecoder(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        return delegate().decode(token);
    }

    private NimbusJwtDecoder delegate() {
        NimbusJwtDecoder current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (delegate == null) {
                delegate = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
            }
            return delegate;
        }
    }
}
