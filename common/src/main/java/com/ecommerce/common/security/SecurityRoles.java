package com.ecommerce.common.security;

/**
 * Role constants used across the platform. Authorities carry the standard
 * {@code ROLE_} prefix; roles map 1:1 to Keycloak realm roles.
 */
public final class SecurityRoles {

    public static final String CUSTOMER = "ROLE_CUSTOMER";
    public static final String ADMIN = "ROLE_ADMIN";
    public static final String SERVICE = "ROLE_SERVICE";

    private SecurityRoles() {
    }
}
