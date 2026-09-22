package com.ecommerce.common.resilience;

import java.time.Duration;
import java.util.Optional;

/**
 * An overall wall-clock budget for the work on the current thread, and the
 * mechanism that stops a saga from spending its whole allowance on one call.
 *
 * <p>Per-call timeouts alone cannot bound a saga: six calls with a 1.5 s
 * response timeout and two retries each is 27 s of worst case. The deadline is
 * the missing outer bound. It is set once, at the start of the saga, and then:
 *
 * <ul>
 *   <li>{@link ResilienceRequestInterceptor} refuses to start a call once the
 *       budget is spent, failing fast with a typed error the saga can
 *       compensate on; and</li>
 *   <li>the Apache HttpClient request config caps the response timeout at the
 *       remaining budget, so the last call cannot outlive the saga by more than
 *       one socket timeout.</li>
 * </ul>
 *
 * <p>Held in a {@link ThreadLocal} because the saga is synchronous and the
 * callers are the shared REST clients, which have no other way to learn about a
 * caller-specific budget. Work that runs without a deadline (a scheduled job, a
 * startup call) simply gets the per-call timeouts, which is the correct default
 * — see ADR-018.
 */
public final class Deadline {

    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    /** Starts a budget on the current thread and makes it current. */
    public static Deadline start(Duration budget) {
        Deadline deadline = new Deadline(System.nanoTime() + budget.toNanos());
        CURRENT.set(deadline);
        return deadline;
    }

    /** Removes the budget from the current thread; always call in a finally. */
    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Deadline> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The remaining budget, or empty when no budget is in force. */
    public static Optional<Duration> remaining() {
        return current().map(Deadline::timeLeft);
    }

    public Duration timeLeft() {
        return Duration.ofNanos(expiresAtNanos - System.nanoTime());
    }

    public boolean isExpired() {
        return expiresAtNanos - System.nanoTime() <= 0;
    }
}
