// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageLimitServiceTest {

    @Mock private EntitlementService entitlementService;
    private DefaultUsageLimitService service;

    @BeforeEach
    void setUp() {
        service = new DefaultUsageLimitService(entitlementService);
    }

    @Test
    void allows_creation_below_limit() {
        givenLimit(LimitType.DIRECTORIES, 10L);
        service.requireWithinLimit(LimitType.DIRECTORIES, 5L);  // 5 < 10 → ok
    }

    @Test
    void allows_creation_one_below_limit() {
        // Edge case: at 9 directories, we can create one more (→ 10).
        givenLimit(LimitType.DIRECTORIES, 10L);
        service.requireWithinLimit(LimitType.DIRECTORIES, 9L);
    }

    @Test
    void rejects_when_currentCount_equals_limit() {
        // At 10/10, a new create would push us to 11 → reject.
        givenLimit(LimitType.DIRECTORIES, 10L);
        assertThatThrownBy(() -> service.requireWithinLimit(LimitType.DIRECTORIES, 10L))
                .isInstanceOf(LimitExceededException.class)
                .satisfies(ex -> {
                    LimitExceededException lex = (LimitExceededException) ex;
                    assertThat(lex.getLimitType()).isEqualTo(LimitType.DIRECTORIES);
                    assertThat(lex.getCurrentCount()).isEqualTo(10L);
                    assertThat(lex.getMaximum()).isEqualTo(10L);
                });
    }

    @Test
    void rejects_when_currentCount_exceeds_limit() {
        // Grandfathered case: a downgrade left us over the new cap.
        // Existing rows keep working; this method gates *new* creates
        // and should reject until the customer falls back under the cap
        // (by deleting resources or upgrading).
        givenLimit(LimitType.ADMIN_ACCOUNTS, 5L);
        assertThatThrownBy(() -> service.requireWithinLimit(LimitType.ADMIN_ACCOUNTS, 8L))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    void unlimited_license_is_no_op() {
        // Settings-derived licenses always emit an empty limits map →
        // limitFor() returns Long.MAX_VALUE → requireWithinLimit
        // short-circuits without any arithmetic or exception.
        when(entitlementService.current()).thenReturn(new License(
                null, Edition.ENTERPRISE, Set.of(), Map.of(),
                Instant.EPOCH, Instant.MAX, null));

        // Any currentCount — including absurdly large — must pass.
        service.requireWithinLimit(LimitType.DIRECTORIES, Long.MAX_VALUE - 1);
    }

    @Test
    void exception_carries_edition() {
        when(entitlementService.current()).thenReturn(new License(
                UUID.randomUUID(), Edition.BUSINESS, Set.of(),
                Map.of(LimitType.DIRECTORIES, 3L),
                Instant.now(), Instant.now().plusSeconds(86400), "sig"));

        assertThatThrownBy(() -> service.requireWithinLimit(LimitType.DIRECTORIES, 3L))
                .isInstanceOfSatisfying(LimitExceededException.class,
                        ex -> assertThat(ex.getCurrentEdition()).isEqualTo(Edition.BUSINESS));
    }

    @Test
    void different_limit_types_tracked_independently() {
        when(entitlementService.current()).thenReturn(new License(
                null, Edition.BUSINESS, Set.of(),
                Map.of(LimitType.DIRECTORIES, 5L, LimitType.ADMIN_ACCOUNTS, 3L),
                Instant.EPOCH, Instant.MAX, null));

        // DIRECTORIES is at cap, ADMIN_ACCOUNTS is not.
        assertThatThrownBy(() -> service.requireWithinLimit(LimitType.DIRECTORIES, 5L))
                .isInstanceOf(LimitExceededException.class);
        service.requireWithinLimit(LimitType.ADMIN_ACCOUNTS, 2L);  // ok
    }

    private void givenLimit(LimitType type, long max) {
        when(entitlementService.current()).thenReturn(new License(
                null, Edition.BUSINESS, Set.of(),
                Map.of(type, max),
                Instant.EPOCH, Instant.MAX, null));
    }
}
