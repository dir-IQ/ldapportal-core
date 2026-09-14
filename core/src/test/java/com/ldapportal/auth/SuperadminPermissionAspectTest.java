// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.SuperadminPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Pins the class-vs-method resolution the view / manage split relies on:
 * a controller carries its {@code VIEW_*} key at class level and a
 * {@code MANAGE_*} key on each write method, and the method annotation must
 * win so a viewer reaches the reads but not the writes.
 */
@ExtendWith(MockitoExtension.class)
class SuperadminPermissionAspectTest {

    @Mock private PermissionService permissionService;

    private final AuthPrincipal viewer =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "viewer");

    private ViewManageController proxied;

    @RequiresSuperadminPermission(SuperadminPermission.VIEW_DIRECTORIES)
    static class ViewManageController {
        public String list() { return "list"; }

        @RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORIES)
        public String create() { return "created"; }
    }

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new ViewManageController());
        factory.setProxyTargetClass(true);
        factory.addAspect(new SuperadminPermissionAspect(permissionService));
        proxied = factory.getProxy();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewer, null, List.of()));
    }

    @Test
    void readMethod_requiresTheClassLevelViewKey() {
        proxied.list();
        verify(permissionService).requireSuperadminPermission(viewer, SuperadminPermission.VIEW_DIRECTORIES);
    }

    @Test
    void writeMethod_requiresTheMethodLevelManageKey_notTheClassKey() {
        doThrow(new AccessDeniedException("nope"))
                .when(permissionService)
                .requireSuperadminPermission(any(), eq(SuperadminPermission.MANAGE_DIRECTORIES));

        assertThatThrownBy(() -> proxied.create()).isInstanceOf(AccessDeniedException.class);
        verify(permissionService).requireSuperadminPermission(viewer, SuperadminPermission.MANAGE_DIRECTORIES);
    }
}
