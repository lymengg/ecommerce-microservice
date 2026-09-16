package com.ecommerce.gateway.filter;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window token bucket keyed by client. Thread-safe; a single bucket per
 * key per window. Suitable for coarse-grained gateway limiting — a
 * Redis-backed limiter (shared state across gateway instances) replaces this
 * once the Phase 8 infrastructure lands.
 */
public class FixedWindowRateLimiter {

    private final int maxRequestsPerWindow;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxRequestsPerWindow, Duration window) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.window = window;
    }

    public synchronized boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Window current = windows.get(key);
        if (current == null || current.start().isBefore(now.minus(window))) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (current.count() >= maxRequestsPerWindow) {
            return false;
        }
        windows.put(key, new Window(current.start(), current.count() + 1));
        return true;
    }

    public void reset() {
        windows.clear();
    }

    private record Window(Instant start, int count) {
    }
}
