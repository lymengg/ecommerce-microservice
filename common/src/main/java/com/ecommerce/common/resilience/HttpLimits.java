package com.ecommerce.common.resilience;

import java.time.Duration;

/**
 * Transport limits resolved for one dependency: the three Apache HttpClient 5
 * timeouts and the size of that dependency's connection pool.
 *
 * @param connectTimeout           time to establish the TCP connection
 * @param connectionRequestTimeout time to lease a pooled connection
 * @param responseTimeout          socket read timeout, capped by
 *                                 {@link Deadline#remaining()} at request time
 * @param maxConnectionsPerRoute   pool bound for this dependency's endpoint
 * @param maxConnectionsTotal      pool bound across all endpoints of the client
 */
public record HttpLimits(
        Duration connectTimeout,
        Duration connectionRequestTimeout,
        Duration responseTimeout,
        int maxConnectionsPerRoute,
        int maxConnectionsTotal
) {
}
