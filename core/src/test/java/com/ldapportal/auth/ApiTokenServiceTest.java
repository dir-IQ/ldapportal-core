// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApiToken;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.repository.ApiTokenRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiTokenServiceTest {

    @Mock private ApiTokenRepository repository;
    @Mock private AuditService auditService;

    @InjectMocks private ApiTokenService service;

    private Account creator;

    @BeforeEach
    void setUp() {
        creator = new Account();
        creator.setId(UUID.randomUUID());
        creator.setUsername("alice");
        creator.setRole(AccountRole.SUPERADMIN);
        creator.setActive(true);
    }

    @Test
    void create_returnsPlaintextWithCorrectPrefix() {
        when(repository.save(any(ApiToken.class))).thenAnswer(ApiTokenServiceTest::saveWithGeneratedId);

        ApiTokenService.CreateResult result = service.create(
                "ci-terraform", "CI provisioning",
                Instant.now().plus(30, ChronoUnit.DAYS), creator);

        assertThat(result.plaintext()).startsWith("ldap_pat_");
        assertThat(result.plaintext()).hasSize(52); // 9 prefix + 43 base64url
    }

    @Test
    void create_storesSha256OfPlaintext() throws Exception {
        when(repository.save(any(ApiToken.class))).thenAnswer(ApiTokenServiceTest::saveWithGeneratedId);

        ApiTokenService.CreateResult result = service.create(
                "ci-terraform", null,
                Instant.now().plus(30, ChronoUnit.DAYS), creator);

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(repository).save(captor.capture());
        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(result.plaintext().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(captor.getValue().getTokenHash()).isEqualTo(expected);
    }

    @Test
    void create_storedPrefixMatchesFirst16Chars() {
        when(repository.save(any(ApiToken.class))).thenAnswer(ApiTokenServiceTest::saveWithGeneratedId);

        ApiTokenService.CreateResult result = service.create(
                "ci-terraform", null,
                Instant.now().plus(30, ChronoUnit.DAYS), creator);

        ArgumentCaptor<ApiToken> captor = ArgumentCaptor.forClass(ApiToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTokenPrefix())
                .isEqualTo(result.plaintext().substring(0, 16));
    }

    @Test
    void create_rejectsExpiryInPast() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.create("x", null, past, creator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void create_rejectsExpiryOverTwoYears() {
        Instant tooFar = Instant.now().plus(800, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.create("x", null, tooFar, creator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 years");
    }

    @Test
    void create_emitsAuditEvent() {
        when(repository.save(any(ApiToken.class))).thenAnswer(ApiTokenServiceTest::saveWithGeneratedId);

        service.create("ci", null,
                Instant.now().plus(30, ChronoUnit.DAYS), creator);

        verify(auditService).recordSystemEvent(
                any(AuthPrincipal.class),
                org.mockito.ArgumentMatchers.eq(AuditAction.API_TOKEN_CREATED),
                any());
    }

    @Test
    void authenticate_validToken_returnsCreatorPrincipalAndTokenDetails() {
        String plaintext = "ldap_pat_abcdefghij1234567890123456789012345678901234";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().plus(30, ChronoUnit.DAYS));
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        Optional<ApiTokenService.AuthenticationBundle> result = service.authenticate(plaintext);

        assertThat(result).isPresent();
        ApiTokenService.AuthenticationBundle bundle = result.get();
        assertThat(bundle.principal().type()).isEqualTo(PrincipalType.SUPERADMIN);
        assertThat(bundle.principal().id()).isEqualTo(creator.getId());
        assertThat(bundle.principal().username()).isEqualTo("alice");
        assertThat(bundle.authorityRole()).isEqualTo("ROLE_SUPERADMIN");
        assertThat(bundle.details().tokenId()).isEqualTo(stored.getId());
        assertThat(bundle.details().tokenName()).isEqualTo(stored.getName());
    }

    @Test
    void authenticate_wrongPlaintext_returnsEmpty() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThat(service.authenticate("ldap_pat_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
                .isEmpty();
    }

    @Test
    void authenticate_nonPrefixedBearer_returnsEmpty() {
        assertThat(service.authenticate("eyJhbGciOiJIUzI1NiJ9.abc.def"))
                .isEmpty();
        // No DB lookup for non-prefixed bearers
        verify(repository, org.mockito.Mockito.never()).findByTokenHash(any());
    }

    @Test
    void authenticate_nullBearer_returnsEmpty() {
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void authenticate_expiredToken_returnsEmpty() {
        String plaintext = "ldap_pat_expiredxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().minus(1, ChronoUnit.DAYS));
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        assertThat(service.authenticate(plaintext)).isEmpty();
    }

    @Test
    void authenticate_revokedToken_returnsEmpty() {
        String plaintext = "ldap_pat_revokedxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().plus(30, ChronoUnit.DAYS));
        stored.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        assertThat(service.authenticate(plaintext)).isEmpty();
    }

    @Test
    void authenticate_deactivatedCreator_returnsEmpty() {
        String plaintext = "ldap_pat_deactivatedxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().plus(30, ChronoUnit.DAYS));
        creator.setActive(false);
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        assertThat(service.authenticate(plaintext)).isEmpty();
    }

    @Test
    void authenticate_updatesLastUsedAt() {
        String plaintext = "ldap_pat_updatesxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().plus(30, ChronoUnit.DAYS));
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        service.authenticate(plaintext);

        verify(repository).updateLastUsedAt(org.mockito.ArgumentMatchers.eq(stored.getId()),
                any(Instant.class));
    }

    @Test
    void authenticate_debouncesLastUsedAt() {
        String plaintext = "ldap_pat_debouncexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        byte[] h = ApiTokenService.hash(plaintext);
        ApiToken stored = newStoredToken(h, Instant.now().plus(30, ChronoUnit.DAYS));
        when(repository.findByTokenHash(h)).thenReturn(Optional.of(stored));

        service.authenticate(plaintext);
        service.authenticate(plaintext);
        service.authenticate(plaintext);

        // 3 calls, but only 1 DB write (debounce = 1 minute)
        verify(repository, org.mockito.Mockito.times(1))
                .updateLastUsedAt(any(UUID.class), any(Instant.class));
    }

    @Test
    void revoke_setsRevokedAtAndAudits() {
        ApiToken token = newStoredToken(new byte[32], Instant.now().plus(30, ChronoUnit.DAYS));
        when(repository.findById(token.getId())).thenReturn(Optional.of(token));

        service.revoke(token.getId(), principalOf(creator));

        assertThat(token.getRevokedAt()).isNotNull();
        verify(auditService).recordSystemEvent(
                any(AuthPrincipal.class),
                org.mockito.ArgumentMatchers.eq(AuditAction.API_TOKEN_REVOKED),
                any());
    }

    @Test
    void revoke_isIdempotent() {
        ApiToken token = newStoredToken(new byte[32], Instant.now().plus(30, ChronoUnit.DAYS));
        Instant originalRevokedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        token.setRevokedAt(originalRevokedAt);
        when(repository.findById(token.getId())).thenReturn(Optional.of(token));

        service.revoke(token.getId(), principalOf(creator));

        // Second revoke does not overwrite the original timestamp.
        assertThat(token.getRevokedAt()).isEqualTo(originalRevokedAt);
    }

    @Test
    void revoke_unknownId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(unknown, principalOf(creator)))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);
    }

    @Test
    void rotate_returnsNewPlaintextAndReplacesHash() {
        ApiToken token = newStoredToken(new byte[32], Instant.now().plus(30, ChronoUnit.DAYS));
        byte[] originalHash = token.getTokenHash();
        when(repository.findById(token.getId())).thenReturn(Optional.of(token));

        ApiTokenService.CreateResult result = service.rotate(token.getId(), principalOf(creator));

        assertThat(result.plaintext()).startsWith("ldap_pat_");
        assertThat(token.getTokenHash()).isNotEqualTo(originalHash);
        assertThat(token.getTokenHash())
                .isEqualTo(ApiTokenService.hash(result.plaintext()));
        verify(auditService).recordSystemEvent(
                any(AuthPrincipal.class),
                org.mockito.ArgumentMatchers.eq(AuditAction.API_TOKEN_ROTATED),
                any());
    }

    @Test
    void rotate_revokedToken_throwsConflict() {
        ApiToken token = newStoredToken(new byte[32], Instant.now().plus(30, ChronoUnit.DAYS));
        token.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(repository.findById(token.getId())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.rotate(token.getId(), principalOf(creator)))
                .isInstanceOf(com.ldapportal.exception.ConflictException.class);
    }

    @Test
    void list_excludesRevokedByDefault() {
        service.list(false);
        verify(repository).findAllByRevokedAtIsNullOrderByCreatedAtDesc();
    }

    @Test
    void list_includeRevoked_callsFullQuery() {
        service.list(true);
        verify(repository).findAllByOrderByCreatedAtDesc();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AuthPrincipal principalOf(Account account) {
        return new AuthPrincipal(
                account.getRole() == AccountRole.SUPERADMIN
                        ? PrincipalType.SUPERADMIN : PrincipalType.ADMIN,
                account.getId(), account.getUsername());
    }

    private ApiToken newStoredToken(byte[] hash, Instant expiresAt) {
        ApiToken t = new ApiToken();
        t.setId(UUID.randomUUID());
        t.setName("ci-terraform");
        t.setTokenHash(hash);
        t.setTokenPrefix("ldap_pat_testpre");
        t.setCreatedBy(creator);
        t.setExpiresAt(expiresAt);
        return t;
    }

    /**
     * Mock-side mimic of JPA's post-insert behavior: assigns an ID if one
     * isn't already set, then returns the same instance. Matches what
     * Hibernate does on {@code save()} for entities with
     * {@code @GeneratedValue(strategy = UUID)}.
     */
    private static ApiToken saveWithGeneratedId(org.mockito.invocation.InvocationOnMock inv) {
        ApiToken t = inv.getArgument(0);
        if (t.getId() == null) {
            t.setId(UUID.randomUUID());
        }
        return t;
    }
}
