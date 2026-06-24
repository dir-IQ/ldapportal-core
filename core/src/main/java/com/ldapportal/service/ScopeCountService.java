// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Computes per-profile LDAP user/group counts for the admin dashboard,
 * <em>in parallel</em> and behind a <em>short-TTL cache</em>.
 *
 * <p>Two profiles that target the same {@code (directory, OU)} share a single
 * LDAP query (the scope is deduped within a request and memoised across
 * requests for {@code app.dashboard.count-cache-ttl-seconds}). Distinct scopes
 * are counted concurrently on a small bounded pool rather than one-after-another
 * on the request thread — the dashboard's wall-clock becomes the slowest single
 * directory, not the sum of them.</p>
 *
 * <p>Counts come back as {@code -1} when the directory can't be reached; the UI
 * renders that as an em-dash and flags the row unavailable. Failures are never
 * cached, so a directory coming back online is reflected on the next load
 * rather than after the TTL.</p>
 */
@Service
@Slf4j
public class ScopeCountService {

    /** Mirror of the dashboard's historical cap on a single scope count. */
    private static final long MAX_COUNT = 100_000L;

    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final long ttlNanos;
    private final ExecutorService executor;

    /** Memoised counts keyed by {@code kind|directoryId|baseDn}. */
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ScopeCountService(LdapUserService userService,
                             LdapGroupService groupService,
                             @Value("${app.dashboard.count-cache-ttl-seconds:60}") long ttlSeconds,
                             @Value("${app.dashboard.count-parallelism:8}") int parallelism) {
        this.userService = userService;
        this.groupService = groupService;
        this.ttlNanos = Duration.ofSeconds(Math.max(0, ttlSeconds)).toNanos();
        int threads = Math.max(1, parallelism);
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "dashboard-scope-count");
            t.setDaemon(true);
            return t;
        });
    }

    /** User and group counts for one profile's scope ({@code -1} = LDAP failure). */
    public record ScopeCounts(long users, long groups) {}

    /**
     * A request to count one profile's scope. {@code directory} must be a
     * fully-initialised entity (all simple columns loaded) — the count runs on
     * a worker thread outside the caller's persistence context, so it must not
     * trigger lazy loading.
     */
    public record ScopeRequest(UUID profileId,
                               DirectoryConnection directory,
                               String userBaseDn,
                               String groupBaseDn) {}

    private record Cached(long value, long expiresAtNanos) {}

    /**
     * Resolves counts for every request, reusing cached scope counts and
     * counting cache-missed scopes concurrently. Returns a map keyed by
     * {@link ScopeRequest#profileId()}.
     */
    public Map<UUID, ScopeCounts> countAll(List<ScopeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }

        long now = System.nanoTime();

        // One future per distinct scope. Cache hits and disabled directories
        // complete immediately; misses run on the pool. Deduping here is what
        // makes profiles that share an OU cost a single query.
        Map<String, CompletableFuture<Long>> byScope = new HashMap<>();
        for (ScopeRequest req : requests) {
            DirectoryConnection dc = req.directory();
            UUID dirId = dc.getId();
            registerScope(byScope, userKey(dirId, req.userBaseDn()), dc, req.userBaseDn(), true, now);
            registerScope(byScope, groupKey(dirId, req.groupBaseDn()), dc, req.groupBaseDn(), false, now);
        }

        CompletableFuture
                .allOf(byScope.values().toArray(new CompletableFuture[0]))
                .join();

        Map<String, Long> resolved = new HashMap<>(byScope.size());
        byScope.forEach((key, future) -> resolved.put(key, future.join()));

        Map<UUID, ScopeCounts> out = new HashMap<>(requests.size());
        for (ScopeRequest req : requests) {
            UUID dirId = req.directory().getId();
            long users = resolved.getOrDefault(userKey(dirId, req.userBaseDn()), -1L);
            long groups = resolved.getOrDefault(groupKey(dirId, req.groupBaseDn()), -1L);
            out.put(req.profileId(), new ScopeCounts(users, groups));
        }
        return out;
    }

    private void registerScope(Map<String, CompletableFuture<Long>> byScope,
                               String key,
                               DirectoryConnection dc,
                               String baseDn,
                               boolean users,
                               long now) {
        if (byScope.containsKey(key)) {
            return; // already scheduled this scope for the current request
        }

        // Disabled directories never touch LDAP — the count is 0, matching the
        // pre-parallel behaviour. Not cached, so a re-enable shows immediately.
        if (!dc.isEnabled()) {
            byScope.put(key, CompletableFuture.completedFuture(0L));
            return;
        }

        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAtNanos() - now > 0) {
            byScope.put(key, CompletableFuture.completedFuture(cached.value()));
            return;
        }

        CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> {
            try {
                long value = users
                        ? userService.countUsers(dc, baseDn, MAX_COUNT)
                        : groupService.countGroups(dc, baseDn, MAX_COUNT);
                // Cache successes only; -1 failures must be retried next load.
                cache.put(key, new Cached(value, System.nanoTime() + ttlNanos));
                return value;
            } catch (Exception e) {
                log.warn("Failed to count {} for directory {} scope '{}': {}",
                        users ? "users" : "groups", dc.getDisplayName(), baseDn, e.getMessage());
                return -1L;
            }
        }, executor);
        byScope.put(key, future);
    }

    private static String userKey(UUID dirId, String baseDn) {
        return "u|" + dirId + "|" + (baseDn == null ? "" : baseDn);
    }

    private static String groupKey(UUID dirId, String baseDn) {
        return "g|" + dirId + "|" + (baseDn == null ? "" : baseDn);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
