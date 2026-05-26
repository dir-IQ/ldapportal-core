// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.ApiTokenService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.JwtTokenService;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.config.AppProperties;
import com.ldapportal.config.SecurityConfig;
import com.ldapportal.repository.AccountRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

/**
 * Base class for {@code @WebMvcTest} controller tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code @MockitoBean JpaMetamodelMappingContext} — prevents
 *       {@code @EnableJpaAuditing} from failing in the web slice context.</li>
 *   <li>{@code @MockitoBean JwtTokenService} — satisfies
 *       {@link com.ldapportal.auth.JwtAuthenticationFilter}.</li>
 *   <li>{@code @MockitoBean PermissionService} — satisfies
 *       {@link com.ldapportal.auth.FeaturePermissionAspect} if loaded.</li>
 *   <li>Shared test property source with required {@code app.*} config.</li>
 *   <li>Factory methods for superadmin and admin authentication tokens.</li>
 * </ul>
 * </p>
 */
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
@TestPropertySource(properties = {
        "app.jwt.secret=dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdHNlY3JldHRlc3Q=",
        "app.encryption.key=dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdGtleTA=",
        "app.bootstrap.superadmin.username=admin",
        "app.bootstrap.superadmin.password=test"
})
public abstract class BaseControllerTest {

    @MockitoBean
    @SuppressWarnings("unused")
    JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    @SuppressWarnings("unused")
    JwtTokenService jwtTokenService;

    // Satisfies JwtAuthenticationFilter's account re-validation dependency.
    // protected so subclasses in other packages can stub it.
    @MockitoBean
    protected AccountRepository accountRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    protected ApiTokenService apiTokenService;

    @MockitoBean
    @SuppressWarnings("unused")
    PermissionService permissionService;

    // ── Auth helpers ──────────────────────────────────────────────────────────

    protected UsernamePasswordAuthenticationToken superadminAuth() {
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "superadmin");
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    protected UsernamePasswordAuthenticationToken adminAuth() {
        AuthPrincipal p = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
