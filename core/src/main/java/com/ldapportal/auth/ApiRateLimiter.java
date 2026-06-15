// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory per-user rate limiter for expensive API operations such as
 * bulk import/export and report generation.
 *
 * <p>Keyed on the authenticated username so authenticated users are bounded
 * independently.  State is per-instance; for multi-node deployments this
 * should be replaced with a Redis-backed counter.</p>
 */
@Component
public class ApiRateLimiter {

    /** Maximum number of calls allowed per user within {@link #WINDOW_MS}. */
    private static final int  MAX_CALLS  = 10;
    private static final long WINDOW_MS  = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    /**
     * Checks whether {@code username} has exceeded the rate limit for the
     * given {@code operation} label (used only for the error message).
     *
     * @throws TooManyRequestsException if the limit is exceeded
     */
    public void check(String username, String operation) {
        long now = System.currentTimeMillis();
        Deque<Long> times = calls.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= MAX_CALLS) {
                throw new TooManyRequestsException(
                        "Rate limit exceeded for operation '" + operation
                        + "'. Please try again later.");
            }
            times.addLast(now);
        }
        if (times.isEmpty()) {
            calls.remove(username, times);
        }
    }
}
