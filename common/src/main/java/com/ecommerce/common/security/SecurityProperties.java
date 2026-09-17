package com.ecommerce.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Per-service security tuning consumed by {@link ServiceSecurityConfig}.
 *
 * <p>{@code permit-all} lists endpoint matchers that need no authentication
 * (public browsing). Entries are either a plain path pattern
 * ({@code /actuator/health}) or a method-prefixed pattern
 * ({@code GET:/api/v1/products/**}).
 */
@ConfigurationProperties(prefix = "ecommerce.security")
public class SecurityProperties {

    private List<String> permitAll = List.of();

    public List<String> getPermitAll() {
        return permitAll;
    }

    public void setPermitAll(List<String> permitAll) {
        this.permitAll = permitAll != null ? permitAll : List.of();
    }

    public List<RequestMatcher> permitAllMatchers() {
        return permitAll.stream().map(SecurityProperties::toMatcher).toList();
    }

    private static RequestMatcher toMatcher(String spec) {
        int separator = spec.indexOf(':');
        if (separator > 0) {
            try {
                HttpMethod method = HttpMethod.valueOf(spec.substring(0, separator).toUpperCase());
                return AntPathRequestMatcher.antMatcher(method, spec.substring(separator + 1));
            } catch (IllegalArgumentException ignored) {
                // not a method prefix — treat the whole string as a path pattern
            }
        }
        return AntPathRequestMatcher.antMatcher(spec);
    }
}
