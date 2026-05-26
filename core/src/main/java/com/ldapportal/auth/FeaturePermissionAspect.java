// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.FeatureKey;
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

import java.lang.reflect.Parameter;
import java.util.UUID;

/**
 * AOP aspect that enforces {@link RequiresFeature} on annotated methods.
 *
 * <p>Before the method executes the aspect:</p>
 * <ol>
 *   <li>Retrieves the {@link AuthPrincipal} from {@link SecurityContextHolder}.</li>
 *   <li>Locates the {@code directoryId} parameter (type {@link UUID}) in the
 *       method signature — required for dimension 1/2 checks.</li>
 *   <li>Delegates to {@link PermissionService#requireFeature}.</li>
 * </ol>
 *
 * <p>Throws {@link AccessDeniedException} if access is denied, which Spring
 * Security translates to an HTTP 403 response.</p>
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class FeaturePermissionAspect {

    private final PermissionService permissionService;

    @Before("@annotation(requiresFeature)")
    public void checkFeaturePermission(JoinPoint jp, RequiresFeature requiresFeature) {
        AuthPrincipal principal = extractPrincipal();
        FeatureKey    feature   = requiresFeature.value();
        UUID          dirId     = extractDirectoryId(jp);

        permissionService.requireFeature(principal, dirId, feature);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuthPrincipal extractPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        return principal;
    }

    /**
     * Finds the {@link UUID} parameter that identifies the directory.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>A parameter annotated with {@link DirectoryId}</li>
     *   <li>A {@code UUID} parameter named {@code directoryId} (legacy fallback)</li>
     * </ol>
     *
     * @throws IllegalStateException if no suitable parameter is found
     */
    private UUID extractDirectoryId(JoinPoint jp) {
        MethodSignature sig        = (MethodSignature) jp.getSignature();
        Parameter[]     parameters = sig.getMethod().getParameters();
        Object[]        args       = jp.getArgs();

        // Prefer @DirectoryId annotation
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(DirectoryId.class)) {
                if (args[i] instanceof UUID uid) {
                    return uid;
                }
            }
        }

        // Fallback: UUID parameter named "directoryId"
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            if (UUID.class.equals(p.getType()) && "directoryId".equals(p.getName())) {
                if (args[i] instanceof UUID uid) {
                    return uid;
                }
            }
        }

        throw new IllegalStateException(
                "@RequiresFeature method [" + sig.getMethod().getName()
                + "] must have a UUID parameter annotated with @DirectoryId "
                + "or named 'directoryId'");
    }
}
