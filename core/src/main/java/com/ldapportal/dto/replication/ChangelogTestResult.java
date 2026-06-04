// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

/**
 * Result of a changelog capability probe (§7A.11 / §9). {@code reachable} is
 * false — with a diagnostic {@code message} — when the changelog base DN can't
 * be read, its entries lack {@code changeNumber}, or (critically) the root DSE
 * doesn't expose {@code first}/{@code lastChangeNumber}, without which gap and
 * cursor-reset detection are blind. On success it reports the current head.
 */
public record ChangelogTestResult(
        boolean reachable,
        String message,
        long elapsedMs,
        Long currentHead,
        Long firstChangeNumber) {
}
