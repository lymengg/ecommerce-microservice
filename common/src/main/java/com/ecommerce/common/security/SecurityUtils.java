package com.ecommerce.common.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Helpers for reading the authenticated caller. For user tokens the JWT
 * {@code sub} claim carries the customer id; for client-credentials tokens it
 * carries the client id, so callers must discriminate via roles (SERVICE) when
 * identity semantics matter.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }

    /**
     * The JWT {@code sub} claim, or {@code null} for unauthenticated callers.
     */
    public static String currentSubject() {
        Authentication authentication = authentication();
        return authentication != null ? authentication.getName() : null;
    }

    /**
     * The authenticated customer id, or {@code null} when the caller is not a
     * user token (SERVICE client credentials have no customer id).
     */
    public static UUID currentCustomerId() {
        String subject = currentSubject();
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean hasRole(String role) {
        Authentication authentication = authentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }

    public static boolean isService() {
        return hasRole(SecurityRoles.SERVICE);
    }
}
