package com.ecommerce.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthoritiesConverterTest {

    private final KeycloakJwtAuthoritiesConverter converter = new KeycloakJwtAuthoritiesConverter();

    @Test
    void mapsRealmAccessRolesToPrefixedAuthorities() {
        Jwt jwt = jwtWithRoles("CUSTOMER", "ADMIN");

        List<String> authorities = converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).containsExactly("ROLE_CUSTOMER", "ROLE_ADMIN");
    }

    @Test
    void ignoresNonStringRoleEntries() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("customer-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("realm_access", Map.of("roles", java.util.Arrays.asList("CUSTOMER", 42, null)))
                .build();

        List<String> authorities = converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertThat(authorities).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void returnsEmptyForTokenWithoutRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("customer-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("customer-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("realm_access", Map.of())
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRoles(String... roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("customer-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }
}
