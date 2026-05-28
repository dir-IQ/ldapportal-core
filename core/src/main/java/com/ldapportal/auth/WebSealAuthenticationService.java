// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.ApplicationSettingsRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates requests pre-authenticated by IBM Verify Identity Access
 * (formerly ISAM) WebSEAL.
 *
 * <h2>Trust model — pre-provisioning</h2>
 * <ol>
 *   <li>WebSEAL junctions the request to this app after terminating its own
 *       authentication. It injects {@code iv-user} (and optionally
 *       {@code iv-groups}) headers identifying the authenticated principal.</li>
 *   <li>This service trusts those headers <em>only</em> when the request's
 *       immediate peer IP ({@link HttpServletRequest#getRemoteAddr()}) is in
 *       the configured CIDR allow-list. The list MUST be populated for the
 *       feature to do anything — an empty list disables the path at runtime
 *       regardless of {@code enabledAuthTypes}.</li>
 *   <li>The {@code iv-user} value is matched against an existing local admin
 *       account with {@link AccountType#WEBSEAL}. There is no auto-provisioning:
 *       an admin must pre-create the account with the exact username the IdP
 *       will return.</li>
 *   <li>{@code iv-groups} is parsed and logged for audit visibility but is
 *       <strong>never</strong> consulted for role or permission assignment —
 *       app-side profile roles are the sole source of authorization truth.</li>
 * </ol>
 *
 * <p>Header names are resolved case-insensitively via the Servlet API
 * ({@link HttpServletRequest#getHeader}), so WebSEAL's default lowercase
 * emission works without special handling.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebSealAuthenticationService {

    private final ApplicationSettingsRepository settingsRepo;
    private final AccountRepository             accountRepo;
    private final JwtTokenService               jwtTokenService;

    /** Matches both {@code groupA,groupB} and {@code "cn=a,ou=g","cn=b,ou=g"}. */
    private static final Pattern QUOTED_GROUP = Pattern.compile("\"([^\"]+)\"");

    public record WebSealLoginResult(String token, String username, String accountType, String id) {}

    /**
     * Try to authenticate the given request via WebSEAL headers. Returns
     * empty when the feature is disabled, the peer is untrusted, or no
     * iv-user header is present. Throws {@link BadCredentialsException} when
     * the trust check passes but the header names a user with no linked
     * WEBSEAL-type admin account (the caller should turn that into a 401).
     */
    @Transactional
    public Optional<WebSealLoginResult> authenticate(HttpServletRequest request) {
        ApplicationSettings settings = settingsRepo.findFirstBy().orElse(null);
        if (settings == null) return Optional.empty();
        if (!settings.getEnabledAuthTypes().contains(AccountType.WEBSEAL)) return Optional.empty();

        CidrChecker trust = CidrChecker.parse(settings.getWebsealTrustedProxies());
        if (trust.isEmpty()) {
            // Empty allow-list = fail closed. Prevents a deployment mistake
            // (feature ticked on but no proxies configured) from silently
            // accepting arbitrary iv-user headers.
            return Optional.empty();
        }

        String peer = request.getRemoteAddr();
        if (!trust.contains(peer)) return Optional.empty();

        String userHeader  = headerName(settings.getWebsealUserHeader(),   "iv-user");
        String groupHeader = headerName(settings.getWebsealGroupsHeader(), "iv-groups");

        String rawUser = request.getHeader(userHeader);
        if (rawUser == null || rawUser.isBlank()) return Optional.empty();
        final String username = rawUser.trim();

        // Pre-provisioning: the account must exist with authType=WEBSEAL and
        // be active. No groups → roles mapping; roles come from the stored
        // Account row's profile assignments, same as LOCAL/LDAP/OIDC admins.
        Account account = accountRepo.findByUsernameAndActiveTrue(username)
                .filter(a -> a.getAuthType() == AccountType.WEBSEAL)
                .orElseThrow(() -> new BadCredentialsException(
                        "No active WebSEAL-linked admin account matches iv-user=" + username));

        account.setLastLoginAt(Instant.now());

        // Audit-only group logging — never used for authorization decisions.
        String rawGroups = request.getHeader(groupHeader);
        if (rawGroups != null && !rawGroups.isBlank()) {
            List<String> groups = parseGroups(rawGroups);
            log.info("WebSEAL sign-in: user={} groups={} (groups are audit-only in pre-provisioning mode)",
                    username, groups);
        } else {
            log.info("WebSEAL sign-in: user={} (no groups header)", username);
        }

        PrincipalType type = account.getRole() == AccountRole.SUPERADMIN
                ? PrincipalType.SUPERADMIN : PrincipalType.ADMIN;
        AuthPrincipal principal = new AuthPrincipal(type, account.getId(), account.getUsername());
        String token = jwtTokenService.issue(principal, account.getCredentialsVersion());

        return Optional.of(new WebSealLoginResult(
                token, principal.username(), principal.type().name(), principal.id().toString()));
    }

    /**
     * Build the WebSEAL logout URL for the given principal, if applicable.
     * Returns the configured {@code websealLogoutUrl} (default
     * {@code /pkmslogout}) so the frontend can redirect the browser there
     * after clearing its own JWT cookie.
     */
    public Optional<String> logoutUrlFor(AccountType principalAuthType) {
        if (principalAuthType != AccountType.WEBSEAL) return Optional.empty();
        return settingsRepo.findFirstBy()
                .map(ApplicationSettings::getWebsealLogoutUrl)
                .filter(url -> url != null && !url.isBlank());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String headerName(String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured.trim();
    }

    /**
     * Parses {@code iv-groups}. Supports both WebSEAL emissions:
     * <ul>
     *   <li>Plain comma: {@code "groupA,groupB"}</li>
     *   <li>Quoted comma (used when group names contain commas, e.g. LDAP
     *       DNs): {@code "\"cn=a,ou=g\",\"cn=b,ou=g\""}</li>
     * </ul>
     * Never throws — malformed input yields whatever extraction produced.
     */
    static List<String> parseGroups(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        // If the value contains quoted segments, use those as atomic groups
        // so embedded commas don't split a DN.
        if (raw.contains("\"")) {
            List<String> out = new ArrayList<>();
            Matcher m = QUOTED_GROUP.matcher(raw);
            while (m.find()) out.add(m.group(1));
            if (!out.isEmpty()) return out;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
