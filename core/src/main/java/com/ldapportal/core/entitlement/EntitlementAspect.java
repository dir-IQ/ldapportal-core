// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * AOP aspect enforcing {@link Entitled} on annotated methods and classes.
 *
 * <p>Before the target executes, resolves the most specific annotation
 * (method-level overrides class-level) and calls
 * {@link EntitlementService#requireEntitlement(Entitlement)}. Throws
 * {@link EntitlementMissingException} if the current license does not grant
 * it. The global exception handler maps that to HTTP 402.</p>
 *
 * <p>Mirrors the shape of {@link com.ldapportal.auth.FeaturePermissionAspect}
 * but at a coarser granularity: {@code @RequiresFeature} is per-user
 * authorisation, {@code @Entitled} is per-tenant licensing. Both typically
 * apply — the licensing check happens first (earlier in the Spring advice
 * chain, since it runs against less state).</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class EntitlementAspect {

    private final EntitlementService entitlementService;

    /**
     * Fires on any call to a method that carries {@link Entitled} or is
     * declared in a type that carries {@link Entitled}. The single pointcut
     * covers both cases; {@link #resolveAnnotation} picks whichever is more
     * specific.
     */
    @Before("@annotation(com.ldapportal.core.entitlement.Entitled) "
          + "|| @within(com.ldapportal.core.entitlement.Entitled)")
    public void checkEntitled(JoinPoint jp) {
        Entitled annotation = resolveAnnotation(jp);
        if (annotation != null) {
            entitlementService.requireEntitlement(annotation.value());
        }
    }

    /**
     * Method-level annotation wins over class-level. If neither is present
     * the pointcut wouldn't have matched, so the defensive null return is
     * only exercised by unusual weaving edge cases.
     */
    private static Entitled resolveAnnotation(JoinPoint jp) {
        MethodSignature sig = (MethodSignature) jp.getSignature();
        Method method = sig.getMethod();

        Entitled onMethod = method.getAnnotation(Entitled.class);
        if (onMethod != null) return onMethod;

        return method.getDeclaringClass().getAnnotation(Entitled.class);
    }
}
