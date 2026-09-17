package com.ecommerce.integration;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper: populates the SecurityContext with a JWT-shaped principal so
 * services can be exercised through direct calls (without HTTP/MockMvc) the
 * same way a real request would authenticate them. Mirrors the Keycloak
 * {@code realm_access.roles} claim shape.
 */
public final class TestSecurity {

    private TestSecurity() {
    }

    public static void asUser(UUID subject, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(token(subject.toString(), roles));
    }

    public static JwtAuthenticationToken token(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
