// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.SuperadminPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresSuperadminPermission} on annotated methods and
 * classes. Mirrors {@link FeaturePermissionAspect} but for the system-scoped
 * superadmin permission model (no directory dimension).
 *
 * <p>A method-level annotation wins over a class-level one. The pointcut binds
 * both so a single check fires regardless of where the annotation sits.</p>
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class SuperadminPermissionAspect {

    private final PermissionService permissionService;

    @Before("@annotation(com.ldapportal.auth.RequiresSuperadminPermission) "
            + "|| @within(com.ldapportal.auth.RequiresSuperadminPermission)")
    public void check(JoinPoint jp) {
        SuperadminPermission required = resolveRequired(jp);
        if (required == null) return; // defensive — pointcut guarantees one exists

        AuthPrincipal principal = extractPrincipal();
        permissionService.requireSuperadminPermission(principal, required);
    }

    /** Method annotation takes precedence over the declaring class annotation. */
    private SuperadminPermission resolveRequired(JoinPoint jp) {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        RequiresSuperadminPermission onMethod = method.getAnnotation(RequiresSuperadminPermission.class);
        if (onMethod != null) return onMethod.value();
        RequiresSuperadminPermission onClass =
                method.getDeclaringClass().getAnnotation(RequiresSuperadminPermission.class);
        return onClass != null ? onClass.value() : null;
    }

    private AuthPrincipal extractPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        return principal;
    }
}
