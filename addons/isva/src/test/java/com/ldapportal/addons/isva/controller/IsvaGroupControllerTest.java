// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.ImportGroupRequest;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrationQuery;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrations;
import com.ldapportal.addons.isva.dto.IsvaGroupStatus;
import com.ldapportal.addons.isva.service.IsvaGroupService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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
 * Wiring tests for {@link IsvaGroupController}, in the style of
 * {@code IsvaAccountControllerTest}: the DN scope check runs before the
 * service, and a denied scope never reaches it.
 */
@ExtendWith(MockitoExtension.class)
class IsvaGroupControllerTest {

    private static final UUID DIR_ID = UUID.randomUUID();
    private static final String DN = "cn=Engineers,o=groups,c=us";

    @Mock private IsvaGroupService groupService;
    @Mock private PermissionService permissionService;

    private IsvaGroupController controller;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new IsvaGroupController(groupService, permissionService);
        principal = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "ops-alice");
    }

    @Test
    void import_checksScopeThenImportsUnderTheGivenName() {
        IsvaGroupStatus done = new IsvaGroupStatus(true, "cn=Eng,cn=Groups,secAuthority=Default", true, null);
        when(groupService.importGroup(DIR_ID, DN, "Eng", principal)).thenReturn(done);

        assertThat(controller.importGroup(DIR_ID, principal, DN, new ImportGroupRequest("Eng"))).isSameAs(done);

        InOrder order = inOrder(permissionService, groupService);
        order.verify(permissionService).requireDnWithinScope(principal, DIR_ID, DN);
        order.verify(groupService).importGroup(DIR_ID, DN, "Eng", principal);
    }

    @Test
    void import_withoutABody_usesTheDefaultName() {
        controller.importGroup(DIR_ID, principal, DN, null);

        verify(groupService).importGroup(DIR_ID, DN, null, principal);
    }

    @Test
    void import_scopeDenied_doesNotCallTheService() {
        doThrow(new AccessDeniedException("out of scope"))
                .when(permissionService).requireDnWithinScope(principal, DIR_ID, DN);

        assertThatThrownBy(() -> controller.importGroup(DIR_ID, principal, DN, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(groupService);
    }

    @Test
    void status_scopeDenied_doesNotCallTheService() {
        doThrow(new AccessDeniedException("out of scope"))
                .when(permissionService).requireDnWithinScope(principal, DIR_ID, DN);

        assertThatThrownBy(() -> controller.getStatus(DIR_ID, principal, DN))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(groupService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrations_filtersByTheCallersScope() {
        when(groupService.registrations(eq(DIR_ID), eq(List.of(DN)), any()))
                .thenReturn(new IsvaGroupRegistrations(true, true, List.of(DN)));
        when(permissionService.isDnWithinScope(principal, DIR_ID, DN)).thenReturn(false);

        controller.registrations(DIR_ID, principal, new IsvaGroupRegistrationQuery(List.of(DN)));

        ArgumentCaptor<Predicate<String>> visible = ArgumentCaptor.forClass(Predicate.class);
        verify(groupService).registrations(eq(DIR_ID), eq(List.of(DN)), visible.capture());
        assertThat(visible.getValue().test(DN)).isFalse();
    }
}
