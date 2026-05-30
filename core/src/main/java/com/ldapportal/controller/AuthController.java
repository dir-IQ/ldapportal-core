// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.AuthenticationService;
import com.ldapportal.auth.JwtTokenService;
import com.ldapportal.auth.LoginRateLimiter;
import com.ldapportal.auth.OidcAuthenticationService;
import com.ldapportal.auth.WebSealAuthenticationService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.auth.dto.LoginRequest;
import com.ldapportal.auth.dto.LoginResponse;
import com.ldapportal.config.AppProperties;
import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication endpoints.
 *
 * <pre>
 *   POST /api/auth/login         — issue a JWT (set as httpOnly cookie; body carries principal info)
 *   POST /api/auth/logout        — clear the JWT cookie
 *   GET  /api/auth/me            — return current principal info
 *   GET  /api/auth/me/profiles   — return profiles the current principal is authorized for
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String JWT_COOKIE = "jwt";

    private final AuthenticationService          authenticationService;
    private final OidcAuthenticationService     oidcAuthenticationService;
    private final WebSealAuthenticationService  webSealAuthenticationService;
    private final JwtTokenService               jwtTokenService;
    private final LoginRateLimiter              rateLimiter;
    private final AppProperties                 appProperties;
    private final AdminProfileRoleRepository    profileRoleRepo;
    private final ProvisioningProfileRepository profileRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final LdapConnectionFactory         ldapConnectionFactory;
    private final LdapUserService               ldapUserService;
    private final AccountRepository                              accountRepo;
    private final AdminFeaturePermissionRepository               featurePermRepo;
    private final PasswordEncoder                                passwordEncoder;
    private final com.ldapportal.service.ApplicationSettingsService applicationSettingsService;
    private final com.ldapportal.service.AuditService             auditService;
    private final com.ldapportal.core.entitlement.EntitlementService entitlementService;

    /**
     * Public endpoint — returns whether the first-run setup wizard has been completed.
     *
     * <p>Self-healing: if the persisted flag is {@code false} but at least one
     * directory connection exists, the install is effectively past first-run
     * regardless of whether the wizard was formally completed. Flip the flag
     * once and return {@code true}. Closes a class of bugs where users
     * configure directories outside the wizard, get trapped in the wizard
     * later, or mutate a directory in a way that re-evaluates the (stale)
     * flag against a fresh router-guard pass.</p>
     */
    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        boolean flagComplete = applicationSettingsService.getEntity().isSetupCompleted();
        if (!flagComplete && dirRepo.count() > 0) {
            applicationSettingsService.markSetupComplete();
            flagComplete = true;
        }
        return Map.of("setupCompleted", flagComplete);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest  request,
            HttpServletResponse response) {

        rateLimiter.check(request);

        LoginResponse resp = authenticationService.login(req);

        ResponseCookie cookie = ResponseCookie.from(JWT_COOKIE, resp.token())
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(Duration.ofMinutes(appProperties.getJwt().getExpiryMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(resp);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Build external logout URL before we blow away the principal.
        // Two sources possible, depending on how the user authenticated:
        //   * OIDC: redirect to the IdP's end_session_endpoint (with
        //     id_token_hint + post_logout_redirect_uri, and refresh-token
        //     revocation as a side effect) so the IdP terminates its session
        //     too — otherwise the user silently re-authenticates next visit.
        //   * WEBSEAL: redirect to WebSEAL's /pkmslogout (or configured
        //     equivalent) so the reverse proxy clears its session cookie.
        //     Without this, a refresh brings the user right back in because
        //     WebSEAL still trusts the browser.
        String logoutUrl = null;
        if (principal != null) {
            String postLogoutRedirectUri = buildPostLogoutRedirectUri(request);
            logoutUrl = oidcAuthenticationService
                    .buildLogoutUrl(principal.id(), postLogoutRedirectUri)
                    .orElse(null);
            if (logoutUrl == null) {
                AccountType authType = accountRepo.findById(principal.id())
                        .map(Account::getAuthType)
                        .orElse(null);
                if (authType != null) {
                    logoutUrl = webSealAuthenticationService.logoutUrlFor(authType).orElse(null);
                }
            }
        }

        ResponseCookie cookie = ResponseCookie.from(JWT_COOKIE, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Map<String, String> body = new LinkedHashMap<>();
        if (logoutUrl != null) body.put("logoutUrl", logoutUrl);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal AuthPrincipal principal) {

        if (principal == null) {
            throw new BadCredentialsException("Not authenticated");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username",    principal.username());
        body.put("accountType", principal.type().name());
        body.put("id",          principal.id().toString());
        if (principal.dn() != null) {
            body.put("dn", principal.dn());
        }
        if (principal.directoryId() != null) {
            body.put("directoryId", principal.directoryId().toString());
        }
        // Include user preferences and account info
        accountRepo.findById(principal.id()).ifPresent(acct -> {
            body.put("themePreference", acct.getThemePreference() != null ? acct.getThemePreference() : "system");
            body.put("densityPreference", acct.getDensityPreference() != null ? acct.getDensityPreference() : "comfortable");
            body.put("authType", acct.getAuthType().name());
            body.put("email", acct.getEmail());
            body.put("displayName", acct.getDisplayName());
        });

        // Include effective feature permissions (mirrors PermissionService.requireFeature logic)
        if (principal.isSuperadmin()) {
            body.put("features", java.util.Arrays.stream(com.ldapportal.entity.enums.FeatureKey.values())
                    .map(com.ldapportal.entity.enums.FeatureKey::getDbValue)
                    .toList());
        } else if (principal.type() == PrincipalType.SELF_SERVICE) {
            body.put("features", List.of());
        } else {
            // Build explicit override map
            Map<com.ldapportal.entity.enums.FeatureKey, Boolean> overrides = new java.util.EnumMap<>(com.ldapportal.entity.enums.FeatureKey.class);
            for (AdminFeaturePermission p : featurePermRepo.findAllByAdminAccountId(principal.id())) {
                overrides.put(p.getFeatureKey(), p.isEnabled());
            }
            // Determine base role — ADMIN gets all features by default, READ_ONLY gets subset
            boolean hasAdminRole = profileRoleRepo
                    .existsByAdminAccountIdAndBaseRole(principal.id(), com.ldapportal.entity.enums.BaseRole.ADMIN);
            java.util.Set<String> readOnlyDefaults = java.util.Set.of(
                    "bulk.export", "reports.run", "directory.browse", "schema.read",
                    "user.read", "group.read", "approval.manage");

            List<String> features = java.util.Arrays.stream(com.ldapportal.entity.enums.FeatureKey.values())
                    .filter(fk -> {
                        Boolean override = overrides.get(fk);
                        if (override != null) return override; // explicit override
                        return hasAdminRole || readOnlyDefaults.contains(fk.getDbValue()); // base role
                    })
                    .map(com.ldapportal.entity.enums.FeatureKey::getDbValue)
                    .toList();
            body.put("features", features);
        }

        // Feature module toggles. Sourced from the entitlement layer so the
        // wire shape is independent of the underlying storage (Phase 1 reads
        // from ApplicationSettings; Phase 6 swaps in a signed-license provider
        // without changing this endpoint).
        try {
            body.put("hrIntegrationEnabled",
                    entitlementService.has(com.ldapportal.core.entitlement.Entitlement.HR_SYNC));
            body.put("complianceEnabled",
                    entitlementService.has(com.ldapportal.core.entitlement.Entitlement.GOVERNANCE));
            body.put("alertingEnabled",
                    entitlementService.has(com.ldapportal.core.entitlement.Entitlement.ALERTING));
            body.put("directorySyncEnabled",
                    entitlementService.has(com.ldapportal.core.entitlement.Entitlement.DIRECTORY_SYNC));
            // Addon-granted (classpath-probed). Default to false at the
            // fallback below — unlike tier entitlements where missing
            // info means "assume on", an addon's absence is the safer
            // default: don't show an addon-specific UI if we can't
            // confirm the addon is loaded.
            body.put("isvaIntegrationEnabled",
                    entitlementService.has(com.ldapportal.core.entitlement.Entitlement.VENDOR_INTEGRATIONS_ISVA));
        } catch (Exception ignored) {
            body.put("hrIntegrationEnabled", true);
            body.put("complianceEnabled", true);
            body.put("alertingEnabled", true);
            body.put("isvaIntegrationEnabled", false);
        }

        // Distribution probe — true on the community jar (no ee/ classes
        // on the classpath), false on the commercial jar. Same signal
        // LicenseAutoConfiguration uses to pick the right LicenseProvider.
        // Frontend uses this to hide UI that's only meaningful on
        // commercial (e.g. the Feature Modules settings panel, whose
        // toggles are inert on community since the LicenseProvider
        // ignores them).
        body.put("communityDistribution",
                !org.springframework.util.ClassUtils.isPresent(
                        "com.ldapportal.ee.EeEditionMarker",
                        getClass().getClassLoader()));

        // Operator-controlled UX flags surfaced from ApplicationSettings.
        // Not entitlement-gated — these are admin preferences that apply
        // to both editions. Keeping them on /auth/me so the frontend
        // doesn't need a second fetch on every page load.
        try {
            body.put("directorySearchInlineEditEnabled",
                    applicationSettingsService.getEntity().isDirectorySearchInlineEditEnabled());
        } catch (Exception ignored) {
            body.put("directorySearchInlineEditEnabled", true);
        }

        return ResponseEntity.ok(body);
    }

    // ── User preferences ────────────────────────────────────────────────────

    public record UpdatePreferencesRequest(
            String themePreference,
            String densityPreference,
            String displayName,
            String email
    ) {}

    @PostMapping("/me/preferences")
    @Transactional
    public ResponseEntity<Map<String, String>> updatePreferences(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody UpdatePreferencesRequest req) {
        if (principal == null) throw new BadCredentialsException("Not authenticated");

        Account acct = accountRepo.findById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("Account", principal.id()));

        if (req.themePreference() != null) {
            if (!List.of("light", "dark", "system").contains(req.themePreference())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid theme. Must be light, dark, or system."));
            }
            acct.setThemePreference(req.themePreference());
        }
        if (req.densityPreference() != null) {
            if (!List.of("comfortable", "compact").contains(req.densityPreference())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid density. Must be comfortable or compact."));
            }
            acct.setDensityPreference(req.densityPreference());
        }
        if (req.displayName() != null) acct.setDisplayName(req.displayName());
        if (req.email() != null) acct.setEmail(req.email());
        accountRepo.save(acct);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ── Password change (for logged-in admin/superadmin accounts) ────────

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {}

    @PostMapping("/me/change-password")
    @Transactional
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest req) {
        if (principal == null) throw new BadCredentialsException("Not authenticated");

        Account acct = accountRepo.findById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("Account", principal.id()));

        if (acct.getAuthType() == AccountType.LOCAL) {
            // Verify current password
            if (acct.getPasswordHash() == null || !passwordEncoder.matches(req.currentPassword(), acct.getPasswordHash())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect."));
            }
            try {
                com.ldapportal.service.AccountPasswordPolicy.validate(req.newPassword());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
            acct.setPasswordHash(passwordEncoder.encode(req.newPassword()));
            // Bump credentials-version so any other live session for this
            // account (different browser / device) is invalidated.
            Long cv = acct.getCredentialsVersion();
            acct.setCredentialsVersion((cv != null ? cv : 0L) + 1L);
            accountRepo.save(acct);
            auditService.recordSystemEvent(principal,
                    com.ldapportal.entity.enums.AuditAction.PASSWORD_RESET,
                    Map.of("accountId", acct.getId(),
                            "username", acct.getUsername(),
                            "role", acct.getRole().name(),
                            "selfChange", true,
                            "authType", "LOCAL"));
            return ResponseEntity.ok(Map.of("status", "ok"));
        } else if (acct.getAuthType() == AccountType.LDAP && acct.getLdapDn() != null) {
            // LDAP password change: verify current via bind, then reset via service account
            for (DirectoryConnection dc : dirRepo.findAll()) {
                try (LDAPConnection conn = ldapConnectionFactory.openUnboundConnection(dc)) {
                    BindResult result = conn.bind(new SimpleBindRequest(acct.getLdapDn(), req.currentPassword()));
                    if (result.getResultCode() != ResultCode.SUCCESS) continue;
                    // Current password verified — now reset via service account
                    ldapUserService.resetPassword(dc, acct.getLdapDn(), req.newPassword());
                    // Bump credentials-version even though the password
                    // lives in LDAP — JWTs we issued for this account
                    // are still tied to the old credential.
                    Long cv2 = acct.getCredentialsVersion();
                    acct.setCredentialsVersion((cv2 != null ? cv2 : 0L) + 1L);
                    accountRepo.save(acct);
                    auditService.recordSystemEvent(principal,
                            com.ldapportal.entity.enums.AuditAction.PASSWORD_RESET,
                            Map.of("accountId", acct.getId(),
                                    "username", acct.getUsername(),
                                    "role", acct.getRole().name(),
                                    "selfChange", true,
                                    "authType", "LDAP"));
                    return ResponseEntity.ok(Map.of("status", "ok"));
                } catch (Exception ignored) {}
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect or LDAP password change failed."));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Password change not supported for " + acct.getAuthType() + " accounts."));
    }

    // ── Self-service login ─────────────────────────────────────────────────

    public record SelfServiceLoginRequest(
            @NotNull UUID directoryId,
            @NotBlank String username,
            @NotBlank String password) {}

    @PostMapping("/self-service/login")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> selfServiceLogin(
            @Valid @RequestBody SelfServiceLoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        rateLimiter.check(request);

        DirectoryConnection dc = dirRepo.findById(req.directoryId())
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", req.directoryId()));

        if (!dc.isSelfServiceEnabled()) {
            throw new BadCredentialsException("Self-service is not enabled for this directory");
        }

        // Search for the user's DN using the service account
        String loginAttr = dc.getSelfServiceLoginAttribute() != null
                ? dc.getSelfServiceLoginAttribute() : "uid";
        String filter = "(" + loginAttr + "=" + escapeLdapFilter(req.username()) + ")";
        List<LdapUser> users = ldapUserService.searchUsers(dc, filter, dc.getBaseDn(), 1, "dn");
        if (users.isEmpty()) {
            throw new BadCredentialsException("Invalid username or password");
        }
        String userDn = users.get(0).getDn();

        // Bind-as-user to verify password
        try (LDAPConnection conn = ldapConnectionFactory.openUnboundConnection(dc)) {
            BindResult result = conn.bind(new SimpleBindRequest(userDn, req.password()));
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new BadCredentialsException("Invalid username or password");
            }
        } catch (BadCredentialsException e) {
            throw e;
        } catch (Exception e) {
            throw new BadCredentialsException("Invalid username or password");
        }

        // Issue self-service JWT
        UUID syntheticId = UUID.nameUUIDFromBytes(userDn.getBytes(StandardCharsets.UTF_8));
        AuthPrincipal principal = new AuthPrincipal(
                PrincipalType.SELF_SERVICE, syntheticId, req.username(), userDn, dc.getId());
        String token = jwtTokenService.issue(principal);

        ResponseCookie cookie = ResponseCookie.from(JWT_COOKIE, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(Duration.ofMinutes(appProperties.getJwt().getExpiryMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", req.username());
        body.put("accountType", PrincipalType.SELF_SERVICE.name());
        body.put("id", syntheticId.toString());
        body.put("dn", userDn);
        body.put("directoryId", dc.getId().toString());
        return ResponseEntity.ok(body);
    }

    /** Escape special characters in an LDAP filter value per RFC 4515. */
    private static String escapeLdapFilter(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*'  -> sb.append("\\2a");
                case '('  -> sb.append("\\28");
                case ')'  -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── OIDC endpoints ─────────────────────────────────────────────────────

    @GetMapping("/oidc/authorize")
    public ResponseEntity<Map<String, String>> oidcAuthorize(HttpServletRequest request) {
        rateLimiter.check(request);

        String redirectUri = buildOidcRedirectUri(request);

        OidcAuthenticationService.AuthorizeResult result =
                oidcAuthenticationService.buildAuthorizationUrl(redirectUri);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("authorizationUrl", result.authorizationUrl());
        body.put("state", result.state());
        return ResponseEntity.ok(body);
    }

    // ── WebSEAL (IBM Verify Identity Access) pre-auth ──────────────────────

    /**
     * Login probe for requests junctioned through WebSEAL. Returns 200 with
     * a freshly-minted JWT cookie (and a LoginResponse body) when the request
     * carries valid iv-user from a trusted peer, 401 otherwise. The frontend
     * calls this silently on /login mount — success redirects to dashboard,
     * 401 means "fall back to the regular login form".
     */
    @GetMapping("/webseal/authorize")
    public ResponseEntity<LoginResponse> webSealAuthorize(HttpServletRequest request,
                                                          HttpServletResponse response) {
        WebSealAuthenticationService.WebSealLoginResult result;
        try {
            result = webSealAuthenticationService.authenticate(request).orElse(null);
        } catch (BadCredentialsException e) {
            // Per the plan: always 401 (empty body) on any failure path —
            // consistent, standard, minimally revealing.
            return ResponseEntity.status(401).build();
        }
        if (result == null) return ResponseEntity.status(401).build();

        ResponseCookie cookie = ResponseCookie.from(JWT_COOKIE, result.token())
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(Duration.ofMinutes(appProperties.getJwt().getExpiryMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok(new LoginResponse(
                result.token(), result.username(), result.accountType(), result.id()));
    }

    @PostMapping("/oidc/callback")
    public ResponseEntity<LoginResponse> oidcCallback(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        rateLimiter.check(request);

        String code  = body.get("code");
        String state = body.get("state");

        if (code == null || state == null) {
            throw new BadCredentialsException("Missing code or state parameter");
        }

        OidcAuthenticationService.OidcLoginResult result =
                oidcAuthenticationService.handleCallback(code, state);

        ResponseCookie cookie = ResponseCookie.from(JWT_COOKIE, result.token())
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(Duration.ofMinutes(appProperties.getJwt().getExpiryMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        LoginResponse resp = new LoginResponse(result.token(), result.username(),
                result.accountType(), result.id());
        return ResponseEntity.ok(resp);
    }

    /**
     * Resolve the OIDC redirect URI. Prefers the explicit
     * {@code application_settings.oidc_redirect_uri} value so the URI isn't
     * derived from the spoofable HTTP Host header; falls back to
     * request-derived for dev convenience (with a warning at call sites).
     */
    private String buildOidcRedirectUri(HttpServletRequest request) {
        String configured = applicationSettingsService.getEntity().getOidcRedirectUri();
        if (configured != null && !configured.isBlank()) return configured;

        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();

        StringBuilder uri = new StringBuilder(scheme).append("://").append(host);
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            uri.append(":").append(port);
        }
        uri.append("/oidc/callback");
        return uri.toString();
    }

    /**
     * Origin + root of the current request, used as the post-logout redirect
     * when handing the user off to the IdP's end_session_endpoint. We derive
     * this from the explicit OIDC redirect URI when configured (so it has
     * the same trust roots) and only fall back to request-derived otherwise.
     */
    private String buildPostLogoutRedirectUri(HttpServletRequest request) {
        String configured = applicationSettingsService.getEntity().getOidcRedirectUri();
        if (configured != null && !configured.isBlank()) {
            // Strip the /oidc/callback (or similar) path suffix so we land on
            // the app root — the frontend handles post-logout UX from there.
            int pathStart = configured.indexOf('/', configured.indexOf("://") + 3);
            return pathStart > 0 ? configured.substring(0, pathStart) : configured;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        StringBuilder uri = new StringBuilder(scheme).append("://").append(host);
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            uri.append(":").append(port);
        }
        return uri.toString();
    }

    /**
     * Returns the profiles that the current principal is authorized to access.
     * Superadmins see all profiles; admins see only profiles with an assigned profile role.
     */
    @GetMapping("/me/profiles")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myProfiles(
            @AuthenticationPrincipal AuthPrincipal principal) {

        if (principal == null) {
            throw new BadCredentialsException("Not authenticated");
        }

        List<ProvisioningProfile> profiles;

        if (principal.type() == PrincipalType.SUPERADMIN) {
            profiles = profileRepo.findAll();
        } else {
            profiles = profileRoleRepo.findAllByAdminAccountId(principal.id()).stream()
                    .map(AdminProfileRole::getProfile)
                    .toList();
        }

        // The picker is "which profile am I operating against," not a
        // management surface — disabled profiles can't be used to
        // provision, so showing them as options is misleading. The
        // SuperadminProfilesView is the place to manage enable/disable
        // and sees the full list via a separate endpoint.
        return profiles.stream()
                .filter(ProvisioningProfile::isEnabled)
                .sorted(Comparator.comparing(ProvisioningProfile::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("directoryId", p.getDirectory().getId());
                    m.put("directoryType", p.getDirectory().getDirectoryType().name());
                    return m;
                })
                .toList();
    }
}
