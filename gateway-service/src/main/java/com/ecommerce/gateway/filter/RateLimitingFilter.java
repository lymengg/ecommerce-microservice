package com.ecommerce.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Coarse-grained fixed-window rate limit per client IP (RFC 9457-style 429
 * responses). Business-level authorization and per-user limits stay in the
 * services (Phase 4); this only protects the edge.
 */
@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private final boolean enabled;
    private final FixedWindowRateLimiter rateLimiter;

    public RateLimitingFilter(
            @Value("${ecommerce.gateway.rate-limit.enabled:true}") boolean enabled,
            @Value("${ecommerce.gateway.rate-limit.max-requests-per-minute:100}") int maxRequestsPerMinute) {
        this.enabled = enabled;
        this.rateLimiter = new FixedWindowRateLimiter(maxRequestsPerMinute, Duration.ofMinutes(1));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        if (rateLimiter.tryAcquire(clientIp(exchange))) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = """
                {"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded. Try again later."}
                """.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private String clientIp(ServerWebExchange exchange) {
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
