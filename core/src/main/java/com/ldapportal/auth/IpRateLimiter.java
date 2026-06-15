// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-IP rate limiter for unauthenticated endpoints (auditor portal).
 *
 * <p>Uses a sliding window of 10 requests per 60 seconds per IP address.
 * Designed to prevent brute-force token guessing on the public portal routes.</p>
 */
@Component
public class IpRateLimiter {

    private static final int  MAX_CALLS  = 10;
    private static final long WINDOW_MS  = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    /**
     * Checks whether the given IP address has exceeded the rate limit.
     *
     * @param ip the client IP address
     * @throws TooManyRequestsException if the limit is exceeded
     */
    public void check(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> times = calls.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= MAX_CALLS) {
                throw new TooManyRequestsException(
                        "Rate limit exceeded. Please try again later.");
            }
            times.addLast(now);
        }
        if (times.isEmpty()) {
            calls.remove(ip, times);
        }
    }
}
