package com.ecommerce.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Edge rate limiting (OWASP A04/A07): a coarse fixed-window limit per client
 * IP plus a stricter per-subject limit for sensitive business flows (checkout,
 * payment initiation). RFC 9457-style 429 responses. Redis-backed limiting
 * (shared across gateway instances) replaces this once the Phase 8
 * infrastructure lands.
 */
@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final FixedWindowRateLimiter rateLimiter;
    private final FixedWindowRateLimiter strictRateLimiter;

    public RateLimitingFilter(
            @Value("${ecommerce.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${ecommerce.gateway.rate-limit.max-requests-per-minute:100}") int maxRequestsPerMinute,
            @Value("${ecommerce.gateway.rate-limit.strict.max-requests-per-minute:10}") int strictMaxRequestsPerMinute) {
        this.enabled = enabled;
        this.rateLimiter = new FixedWindowRateLimiter(maxRequestsPerMinute, Duration.ofMinutes(1));
        this.strictRateLimiter = new FixedWindowRateLimiter(strictMaxRequestsPerMinute, Duration.ofMinutes(1));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        boolean strict = isStrictPath(path);
        return limitKey(exchange, strict)
                .flatMap(key -> {
                    FixedWindowRateLimiter limiter = strict ? strictRateLimiter : rateLimiter;
                    if (limiter.tryAcquire(key)) {
                        return chain.filter(exchange);
                    }
                    return writeTooManyRequests(exchange.getResponse());
                });
    }

    private Mono<String> limitKey(ServerWebExchange exchange, boolean strict) {
        if (!strict) {
            return Mono.just("ip:" + clientIp(exchange));
        }
        // Strict paths are keyed by the authenticated subject when present so
        // one customer cannot exhaust the shared IP budget, and one NATed IP
        // cannot hide many customers behind it.
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(RateLimitingFilter::isRealUser)
                .map(Authentication::getName)
                .map(subject -> "user:" + subject)
                .defaultIfEmpty("ip:" + clientIp(exchange));
    }

    private static boolean isRealUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean isStrictPath(String path) {
        return path.equals("/api/v1/checkout") || path.startsWith("/api/v1/checkout/")
                || path.equals("/api/v1/payments") || path.startsWith("/api/v1/payments/");
    }

    private static Mono<Void> writeTooManyRequests(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = """
                {"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded. Try again later."}
                """.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
