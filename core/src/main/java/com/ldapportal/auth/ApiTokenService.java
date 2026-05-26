// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApiToken;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.repository.ApiTokenRepository;
import com.ldapportal.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CRUD + authentication logic for long-lived API tokens.
 *
 * <p>Token plaintext is returned to the creator exactly once (from
 * {@link #create}); the database only stores {@link ApiToken#getTokenHash() SHA-256}
 * and a 16-char prefix. See the spec at
 * {@code docs/superpowers/specs/2026-04-22-api-token-auth-design.md}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApiTokenService {

    private static final String PREFIX         = "ldap_pat_";
    private static final int    RANDOM_BYTES   = 32;
    private static final int    PREFIX_LENGTH  = 16;
    private static final Duration MAX_EXPIRY_WINDOW = Duration.ofDays(2 * 365);
    private static final Duration DEBOUNCE         = Duration.ofMinutes(1);

    private final ApiTokenRepository repository;
    private final AuditService auditService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<UUID, Instant> lastPersisted = new ConcurrentHashMap<>();

    // ── public records ────────────────────────────────────────────────────────

    /** Return type of {@link #create} and {@link #rotate}. The plaintext is
     *  returned once and never stored or returned again. */
    public record CreateResult(ApiToken token, String plaintext) {}

    /** Return type of {@link #authenticate}. Carries everything the filter
     *  needs to populate {@link org.springframework.security.core.context.SecurityContextHolder}. */
    public record AuthenticationBundle(
            AuthPrincipal principal,
            ApiTokenAuthenticationDetails details,
            String authorityRole) {}

    // ── create ────────────────────────────────────────────────────────────────

    @Transactional
    public CreateResult create(String name,
                               String description,
                               Instant expiresAt,
                               Account creator) {
        java.util.Objects.requireNonNull(creator, "creator");
        validateExpiry(expiresAt);
        String plaintext = generatePlaintext();
        byte[] tokenHash = hash(plaintext);

        ApiToken token = new ApiToken();
        token.setName(name.trim());
        token.setDescription(description);
        token.setTokenHash(tokenHash);
        token.setTokenPrefix(plaintext.substring(0, PREFIX_LENGTH));
        token.setCreatedBy(creator);
        token.setExpiresAt(expiresAt);
        ApiToken saved = repository.save(token);

        auditService.recordSystemEvent(
                principalOf(creator),
                AuditAction.API_TOKEN_CREATED,
                Map.of("tokenId", saved.getId().toString(),
                       "tokenName", saved.getName()));

        return new CreateResult(saved, plaintext);
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    private void validateExpiry(Instant expiresAt) {
        Instant now = Instant.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("API token expiresAt must be in the future");
        }
        if (expiresAt.isAfter(now.plus(MAX_EXPIRY_WINDOW))) {
            throw new IllegalArgumentException("API token expiresAt cannot exceed 2 years from now");
        }
    }

    private String generatePlaintext() {
        byte[] raw = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(raw);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return PREFIX + body;
    }

    static byte[] hash(String plaintext) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static AuthPrincipal principalOf(Account account) {
        PrincipalType type = account.getRole() == AccountRole.SUPERADMIN
                ? PrincipalType.SUPERADMIN : PrincipalType.ADMIN;
        return new AuthPrincipal(type, account.getId(), account.getUsername());
    }

    // ── authenticate ──────────────────────────────────────────────────────────

    /**
     * Resolves a bearer value into an authenticated principal bundle for the
     * filter to install in {@link org.springframework.security.core.context.SecurityContextHolder}.
     *
     * <p>Returns empty for any invalid case — wrong prefix, unknown hash,
     * revoked token, expired token, deactivated creator. The caller is
     * expected to treat empty as "401 / not authenticated" and pass through
     * so Spring Security's entry point produces the uniform response.</p>
     */
    @Transactional
    public Optional<AuthenticationBundle> authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith(PREFIX)) return Optional.empty();
        byte[] incomingHash = hash(bearer);

        Optional<ApiToken> maybe = repository.findByTokenHash(incomingHash);
        if (maybe.isEmpty()) {
            log.debug("api_token.auth.denied reason=unknown_hash");
            return Optional.empty();
        }

        ApiToken token = maybe.get();
        Instant now = Instant.now();
        if (!token.isActive(now)) {
            log.debug("api_token.auth.denied token_id={} reason={}",
                    token.getId(),
                    token.getRevokedAt() != null ? "revoked" : "expired");
            return Optional.empty();
        }

        Account creator = token.getCreatedBy();
        if (!creator.isActive()) {
            log.debug("api_token.auth.denied token_id={} reason=creator_inactive", token.getId());
            return Optional.empty();
        }

        AuthPrincipal creatorPrincipal = principalOf(creator);
        ApiTokenAuthenticationDetails details =
                new ApiTokenAuthenticationDetails(token.getId(), token.getName());
        String role = "ROLE_" + creatorPrincipal.type().name();

        recordUsageDebounced(token.getId(), now);

        return Optional.of(new AuthenticationBundle(creatorPrincipal, details, role));
    }

    // ── revoke / rotate / list / get ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<ApiToken> list(boolean includeRevoked) {
        return includeRevoked
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findAllByRevokedAtIsNullOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public ApiToken get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new com.ldapportal.exception.ResourceNotFoundException(
                        "ApiToken", id));
    }

    @Transactional
    public void revoke(UUID id, AuthPrincipal actor) {
        ApiToken token = repository.findById(id)
                .orElseThrow(() -> new com.ldapportal.exception.ResourceNotFoundException(
                        "ApiToken", id));
        if (token.getRevokedAt() != null) return;  // idempotent
        token.setRevokedAt(Instant.now());
        lastPersisted.remove(id);
        auditService.recordSystemEvent(
                actor,
                AuditAction.API_TOKEN_REVOKED,
                Map.of("tokenId", id.toString(),
                       "tokenName", token.getName()));
    }

    @Transactional
    public CreateResult rotate(UUID id, AuthPrincipal actor) {
        ApiToken token = repository.findById(id)
                .orElseThrow(() -> new com.ldapportal.exception.ResourceNotFoundException(
                        "ApiToken", id));
        if (token.getRevokedAt() != null) {
            throw new com.ldapportal.exception.ConflictException(
                    "Cannot rotate a revoked token; create a new token instead");
        }

        String plaintext = generatePlaintext();
        token.setTokenHash(hash(plaintext));
        token.setTokenPrefix(plaintext.substring(0, PREFIX_LENGTH));
        lastPersisted.remove(id);

        auditService.recordSystemEvent(
                actor,
                AuditAction.API_TOKEN_ROTATED,
                Map.of("tokenId", id.toString(),
                       "tokenName", token.getName()));
        return new CreateResult(token, plaintext);
    }

    // ── debounced last_used_at ────────────────────────────────────────────────

    void recordUsageDebounced(UUID tokenId, Instant now) {
        Instant previous = lastPersisted.get(tokenId);
        if (previous != null && Duration.between(previous, now).compareTo(DEBOUNCE) < 0) {
            return;
        }
        boolean won = previous == null
                ? lastPersisted.putIfAbsent(tokenId, now) == null
                : lastPersisted.replace(tokenId, previous, now);
        if (!won) return;
        try {
            repository.updateLastUsedAt(tokenId, now);
        } catch (DataAccessException e) {
            log.warn("api_token.last_used_at update failed (tolerable): {}", e.getMessage());
        }
    }
}
