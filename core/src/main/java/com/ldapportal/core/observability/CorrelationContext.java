// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.observability;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-operation correlation id, carried on a {@link ThreadLocal} so every
 * audit row and replication event emitted while handling one top-level
 * operation (an API request, a scheduler tick, a single async dispatch)
 * shares a single trace id.
 *
 * <p>Three entry points set the scope:
 * <ul>
 *   <li><b>API requests</b> — {@code CorrelationFilter} reads
 *       {@code X-Correlation-Id} (or generates one) at request entry.</li>
 *   <li><b>Scheduled / background work</b> — wrap the tick in
 *       {@link #withCorrelation(UUID, Runnable)} so the whole pass shares
 *       one id rather than minting one per LDAP call.</li>
 *   <li><b>Async dispatch</b> — the replication worker opens its own
 *       per-event scope; the originating id travels separately on the
 *       event payload.</li>
 * </ul>
 *
 * <p>The value does not auto-propagate across thread boundaries; a
 * {@code TaskDecorator} copies it onto {@code @Async} worker threads so
 * the audit write (which runs async) inherits the request's id.
 */
public final class CorrelationContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {}

    /** The current correlation id, if a scope is active. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The current correlation id, or a fresh ephemeral one when no scope
     * is active. Unlike a "get-or-install" accessor this does <b>not</b>
     * write the ThreadLocal, so it is safe on the capture hot path: a
     * write driven by a pooled scheduler/async thread (which has no
     * {@link CorrelationFilter} to {@link #clear()} afterwards) won't leak
     * a minted id into the next unrelated task on that thread. Every
     * captured write still gets a non-null id to stamp.
     */
    public static UUID currentOrEphemeral() {
        UUID id = CURRENT.get();
        return id != null ? id : UUID.randomUUID();
    }

    /** Install {@code id} for the current thread (used by the request filter). */
    public static void set(UUID id) {
        CURRENT.set(id);
    }

    /** Clear the current thread's correlation id (used by the request filter's finally). */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Run {@code body} within correlation scope {@code id}, restoring the
     * previous value (or clearing) on exit. Nesting-safe.
     */
    public static <T> T withCorrelation(UUID id, Supplier<T> body) {
        UUID prev = CURRENT.get();
        CURRENT.set(id);
        try {
            return body.get();
        } finally {
            if (prev == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prev);
            }
        }
    }

    /** Void variant of {@link #withCorrelation(UUID, Supplier)}. */
    public static void withCorrelation(UUID id, Runnable body) {
        withCorrelation(id, () -> {
            body.run();
            return null;
        });
    }
}
