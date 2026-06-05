// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.web;

import com.ldapportal.exception.PreconditionFailedException;

/**
 * Helpers for optimistic-concurrency HTTP headers backed by a JPA
 * {@code @Version} counter (§4.4 of the IaC automation plan).
 *
 * <p>A resource's {@code ETag} is its version number rendered as a strong,
 * quoted entity-tag (e.g. {@code "3"}). On a mutating request, a client may
 * send that value back in {@code If-Match}; the write is allowed only while it
 * still matches the stored version, so two concurrent applies — or an apply
 * racing a UI edit — can't silently clobber each other. {@code If-Match} is
 * optional: a request without it falls back to last-write-wins (with the
 * {@code @Version} column still catching a true lost update as a 409), which
 * keeps existing non-IaC callers working unchanged.</p>
 */
public final class ETagSupport {

    private ETagSupport() {}

    /** Render a version as a strong, quoted entity-tag for an {@code ETag} header. */
    public static String format(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Parse an {@code If-Match} header into the version it asserts, or
     * {@code null} when the header is absent (no precondition). Accepts the
     * value with or without surrounding quotes and an optional weak
     * ({@code W/}) prefix. A present-but-unparseable value is a client error
     * (400 via {@link IllegalArgumentException}); {@code *} is rejected the
     * same way because this resource keys preconditions on an explicit
     * version, not "any existing".
     */
    public static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String token = ifMatch.trim();
        if (token.startsWith("W/")) {
            token = token.substring(2).trim();
        }
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1);
        }
        try {
            return Long.parseLong(token.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed If-Match header: " + ifMatch);
        }
    }

    /**
     * Enforce an {@code If-Match} precondition. No-op when {@code expected} is
     * {@code null} (no precondition sent); otherwise a 412 unless it equals the
     * resource's {@code current} version. {@code current} is the entity's
     * {@code @Version}, non-null for any persisted row.
     */
    public static void requireMatch(Long expected, Long current) {
        if (expected != null && !expected.equals(current)) {
            throw new PreconditionFailedException(
                    "If-Match precondition failed: resource is at version " + current
                            + " but " + expected + " was expected; reload and retry");
        }
    }
}
