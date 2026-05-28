// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.dto.IsvaRevokeMode;
import com.ldapportal.addons.isva.dto.RenewRequest;
import com.ldapportal.addons.isva.dto.RevokeRequest;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.service.IsvaAccountRefusalException;
import com.ldapportal.addons.isva.service.IsvaAccountService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wiring tests for {@link IsvaAccountController}. Matches the unit
 * style of {@code IsvaConfigControllerTest} — direct controller
 * instantiation, mocked dependencies — rather than full MockMvc.
 * The {@link com.ldapportal.core.entitlement.Entitled} and
 * {@link com.ldapportal.auth.RequiresFeature} annotations are aspect-
 * driven and covered by framework-level tests in core; what we pin
 * here is verb→service wiring and the unconditional
 * {@link PermissionService#requireDnWithinScope} call.
 *
 * <p>Each verb's authorisation contract is exercised twice: once
 * happy-path to confirm the scope check fires <em>before</em> the
 * service call, and once denied to confirm an
 * {@link AccessDeniedException} short-circuits without touching the
 * service.</p>
 */
@ExtendWith(MockitoExtension.class)
class IsvaAccountControllerTest {

    private static final UUID DIR_ID = UUID.randomUUID();
    private static final String DN = "uid=alice,ou=people,dc=example,dc=com";

    @Mock private IsvaAccountService accountService;
    @Mock private PermissionService permissionService;

    private IsvaAccountController controller;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new IsvaAccountController(accountService, permissionService);
        principal = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "ops-alice");
    }

    // ── getStatus ────────────────────────────────────────────────

    @Test
    void getStatus_checksScopeThenDelegates() {
        IsvaAccountStatus status = IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE);
        when(accountService.getStatus(eq(DIR_ID), eq(DN))).thenReturn(status);

        IsvaAccountStatus got = controller.getStatus(DIR_ID, principal, DN);

        assertThat(got).isSameAs(status);
        InOrder order = inOrder(permissionService, accountService);
        order.verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        order.verify(accountService).getStatus(DIR_ID, DN);
    }

    @Test
    void getStatus_scopeDenied_doesNotCallService() {
        doThrow(new AccessDeniedException("out of scope"))
                .when(permissionService).requireDnWithinScope(principal, DIR_ID, DN);

        assertThatThrownBy(() -> controller.getStatus(DIR_ID, principal, DN))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(accountService);
    }

    // ── grant ────────────────────────────────────────────────────

    @Test
    void grant_checksScopeAndCallsService() {
        IsvaAccountStatus status = IsvaAccountStatus.present(
                IsvaTopologyMode.INLINE, null,
                true, OffsetDateTime.now(ZoneOffset.UTC).plusYears(1),
                true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                "Default");
        when(accountService.grant(eq(DIR_ID), eq(DN), eq(principal))).thenReturn(status);

        IsvaAccountStatus got = controller.grant(DIR_ID, principal, DN);

        assertThat(got).isSameAs(status);
        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).grant(DIR_ID, DN, principal);
    }

    @Test
    void grant_scopeDenied_doesNotCallService() {
        doThrow(new AccessDeniedException("denied"))
                .when(permissionService).requireDnWithinScope(any(), any(), any());

        assertThatThrownBy(() -> controller.grant(DIR_ID, principal, DN))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(accountService);
    }

    // ── revoke (body discriminator) ──────────────────────────────

    @Test
    void revoke_passesRevokeModeFromBody() {
        IsvaAccountStatus status = IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED);
        when(accountService.revoke(eq(DIR_ID), eq(DN), eq(IsvaRevokeMode.HARD), eq(principal)))
                .thenReturn(status);

        IsvaAccountStatus got = controller.revoke(DIR_ID, principal, DN,
                new RevokeRequest(IsvaRevokeMode.HARD));

        assertThat(got).isSameAs(status);
        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).revoke(DIR_ID, DN, IsvaRevokeMode.HARD, principal);
    }

    @Test
    void revoke_soft_isPassedThrough() {
        when(accountService.revoke(any(), any(), eq(IsvaRevokeMode.SOFT), any()))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        controller.revoke(DIR_ID, principal, DN, new RevokeRequest(IsvaRevokeMode.SOFT));

        verify(accountService).revoke(DIR_ID, DN, IsvaRevokeMode.SOFT, principal);
    }

    // ── suspend / restore ───────────────────────────────────────

    @Test
    void suspend_callsService() {
        when(accountService.suspend(any(), any(), any()))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        controller.suspend(DIR_ID, principal, DN);

        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).suspend(DIR_ID, DN, principal);
    }

    @Test
    void restore_callsService() {
        when(accountService.restore(any(), any(), any()))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        controller.restore(DIR_ID, principal, DN);

        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).restore(DIR_ID, DN, principal);
    }

    // ── renew (body carries the new date) ─────────────────────

    @Test
    void renew_passesValidUntilFromBody() {
        OffsetDateTime newValid = OffsetDateTime.now(ZoneOffset.UTC).plusYears(2);
        when(accountService.renew(eq(DIR_ID), eq(DN), eq(newValid), eq(principal)))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        controller.renew(DIR_ID, principal, DN, new RenewRequest(newValid));

        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).renew(DIR_ID, DN, newValid, principal);
    }

    // ── forceCredentialReset ──────────────────────────────────

    @Test
    void forceCredentialReset_callsService() {
        when(accountService.forceCredentialReset(any(), any(), any()))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        controller.forceCredentialReset(DIR_ID, principal, DN);

        verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        verify(accountService).forceCredentialReset(DIR_ID, DN, principal);
    }

    // ── refusal propagation (handler covers the body shape) ───

    @Test
    void serviceRefusalBubbles_handlerWillShapeIt() {
        // The controller doesn't translate IsvaAccountRefusalException —
        // that's IsvaAccountExceptionHandler's job. Here we confirm the
        // exception escapes cleanly so the handler sees it.
        when(accountService.grant(any(), any(), any()))
                .thenThrow(new IsvaAccountRefusalException(
                        HttpStatus.CONFLICT, "ivia_force_off", "force-off"));

        assertThatThrownBy(() -> controller.grant(DIR_ID, principal, DN))
                .isInstanceOf(IsvaAccountRefusalException.class)
                .satisfies(ex -> {
                    IsvaAccountRefusalException r = (IsvaAccountRefusalException) ex;
                    assertThat(r.getCode()).isEqualTo("ivia_force_off");
                    assertThat(r.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}
