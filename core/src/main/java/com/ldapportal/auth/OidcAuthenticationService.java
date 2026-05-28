// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.OidcPendingFlow;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.ApplicationSettingsRepository;
import com.ldapportal.repository.OidcPendingFlowRepository;
import com.ldapportal.service.EncryptionService;
import com.ldapportal.util.UrlValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Handles the OIDC Authorization Code Flow with PKCE.
 *
 * <p>Uses the existing JJWT library (already on the classpath) for ID token
 * validation. Fetches JWKS and discovery documents using {@link HttpClient}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OidcAuthenticationService {

    private final ApplicationSettingsRepository settingsRepo;
    private final AccountRepository             accountRepo;
    private final OidcPendingFlowRepository     pendingFlowRepo;
    private final JwtTokenService               jwtTokenService;
    private final EncryptionService             encryptionService;
    private final ObjectMapper                  objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Duration FLOW_TTL = Duration.ofMinutes(5);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Allow-list of ID-token signing algorithms. Reject {@code none} and
     * symmetric (HMAC) algorithms — accepting an HMAC algorithm with a
     * public key lookup is a classic key-confusion attack.
     */
    private static final Set<String> ALLOWED_ID_TOKEN_ALGORITHMS = Set.of(
            "RS256", "RS384", "RS512",
            "ES256", "ES384", "ES512",
            "PS256", "PS384", "PS512"
    );

    // ── Data classes ─────────────────────────────────────────────────────────

    public record AuthorizeResult(String authorizationUrl, String state) {}

    public record OidcLoginResult(String token, String username, String accountType, String id) {}

    // ── Discovery document cache ─────────────────────────────────────────────

    private volatile Map<String, Object> discoveryCache;
    private volatile String discoveryIssuer;

    /** JWKS cache keyed by the discovery's {@code jwks_uri}. Refetched once
     *  on a kid miss to handle key rotation. */
    private volatile Map<String, Object> jwksCache;
    private volatile String jwksCacheUri;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Initiates the OIDC Authorization Code Flow.
     * Generates state, nonce, PKCE parameters and returns the IdP authorization URL.
     */
    @Transactional
    public AuthorizeResult buildAuthorizationUrl(String redirectUri) {
        ApplicationSettings settings = requireSettings();
        validateOidcConfig(settings);

        Map<String, Object> discovery = fetchDiscovery(settings.getOidcIssuerUrl());
        String authEndpoint = (String) discovery.get("authorization_endpoint");
        if (authEndpoint == null) {
            throw new IllegalStateException("OIDC discovery missing authorization_endpoint");
        }

        String state = generateRandomString(32);
        String nonce = generateRandomString(32);
        String codeVerifier = generateRandomString(64);
        String codeChallenge = computeCodeChallenge(codeVerifier);

        // Opportunistically sweep expired flows so we don't need a scheduled task.
        pendingFlowRepo.deleteExpired(OffsetDateTime.now().minus(FLOW_TTL));

        OidcPendingFlow flow = new OidcPendingFlow();
        flow.setState(state);
        flow.setNonce(nonce);
        flow.setCodeVerifier(codeVerifier);
        flow.setRedirectUri(redirectUri);
        flow.setCreatedAt(OffsetDateTime.now());
        pendingFlowRepo.save(flow);

        String scopes = settings.getOidcScopes() != null ? settings.getOidcScopes() : "openid profile email";

        String url = authEndpoint
                + "?response_type=code"
                + "&client_id=" + urlEncode(settings.getOidcClientId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(scopes)
                + "&state=" + urlEncode(state)
                + "&nonce=" + urlEncode(nonce)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";

        return new AuthorizeResult(url, state);
    }

    /**
     * Completes the OIDC flow: validates state, exchanges code for tokens,
     * validates the ID token, and resolves the matching Account.
     */
    @Transactional
    public OidcLoginResult handleCallback(String code, String state) {
        // 1. Validate state. Consume exactly once — the deleteById below is
        //    what gives us replay protection on the state parameter.
        OidcPendingFlow flow = pendingFlowRepo.findById(state)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired OIDC state"));
        pendingFlowRepo.deleteById(state);

        if (flow.getCreatedAt().isBefore(OffsetDateTime.now().minus(FLOW_TTL))) {
            throw new BadCredentialsException("Invalid or expired OIDC state");
        }

        ApplicationSettings settings = requireSettings();
        validateOidcConfig(settings);

        Map<String, Object> discovery = fetchDiscovery(settings.getOidcIssuerUrl());

        // 2. Exchange code for tokens
        String tokenEndpoint = (String) discovery.get("token_endpoint");
        if (tokenEndpoint == null) {
            throw new IllegalStateException("OIDC discovery missing token_endpoint");
        }

        String clientSecret = settings.getOidcClientSecretEnc() != null
                ? encryptionService.decrypt(settings.getOidcClientSecretEnc())
                : null;

        StringBuilder tokenBody = new StringBuilder();
        tokenBody.append("grant_type=authorization_code");
        tokenBody.append("&code=").append(urlEncode(code));
        tokenBody.append("&redirect_uri=").append(urlEncode(flow.getRedirectUri()));
        tokenBody.append("&client_id=").append(urlEncode(settings.getOidcClientId()));
        if (clientSecret != null) {
            tokenBody.append("&client_secret=").append(urlEncode(clientSecret));
        }
        tokenBody.append("&code_verifier=").append(urlEncode(flow.getCodeVerifier()));

        Map<String, Object> tokenResponse = httpPostForm(tokenEndpoint, tokenBody.toString());

        if (!tokenResponse.containsKey("id_token")) {
            throw new BadCredentialsException("OIDC token exchange failed: no id_token in response");
        }

        String idTokenStr = (String) tokenResponse.get("id_token");
        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");

        // 3. Validate ID token
        Claims claims = validateIdToken(idTokenStr, settings, discovery, flow.getNonce());

        // 3a. UserInfo cross-check (OIDC Core 1.0 §5.3.2 requires that the
        //     sub claim returned from the UserInfo endpoint matches the sub
        //     in the ID token — protects against token-substitution attacks
        //     when we later use UserInfo claims for anything).
        verifyUserInfoSub(discovery, accessToken, claims.getSubject());

        // 4. Extract username claim and resolve account
        String usernameClaim = settings.getOidcUsernameClaim() != null
                ? settings.getOidcUsernameClaim() : "preferred_username";

        Object claimValue = claims.get(usernameClaim);
        if (claimValue == null) {
            throw new BadCredentialsException("ID token missing claim: " + usernameClaim);
        }

        String username = claimValue.toString();

        Account account = accountRepo.findByUsernameAndActiveTrue(username)
                .filter(a -> a.getAuthType() == AccountType.OIDC)
                .orElseThrow(() -> new BadCredentialsException(
                        "No active OIDC account linked to identity: " + username));

        account.setLastLoginAt(Instant.now());
        // Store the ID token so we can hand it to the IdP as id_token_hint on
        // RP-initiated logout (lets the IdP end the session without prompting).
        account.setOidcIdToken(idTokenStr);
        // Store the refresh token (encrypted) if the IdP issued one, so we
        // can call the revocation endpoint on logout. IdPs only return a
        // refresh_token when the client requests offline_access (or the IdP
        // issues one by default) — absent one, we just skip.
        if (refreshToken != null && !refreshToken.isBlank()) {
            account.setOidcRefreshTokenEnc(encryptionService.encrypt(refreshToken));
        }

        PrincipalType type = account.getRole() == AccountRole.SUPERADMIN
                ? PrincipalType.SUPERADMIN : PrincipalType.ADMIN;
        AuthPrincipal principal = new AuthPrincipal(type, account.getId(), account.getUsername());
        String token = jwtTokenService.issue(principal, account.getCredentialsVersion());

        return new OidcLoginResult(token, principal.username(), principal.type().name(),
                principal.id().toString());
    }

    /**
     * Build the RP-initiated logout URL for the given account, if it was
     * last authenticated via OIDC and the IdP advertises an
     * {@code end_session_endpoint} in its discovery document. Returns an
     * empty Optional otherwise. Clears the stored ID token as a side effect.
     */
    @Transactional
    public Optional<String> buildLogoutUrl(UUID accountId, String postLogoutRedirectUri) {
        Account account = accountRepo.findById(accountId).orElse(null);
        if (account == null || account.getAuthType() != AccountType.OIDC) return Optional.empty();

        String idToken = account.getOidcIdToken();
        String encryptedRefresh = account.getOidcRefreshTokenEnc();

        // Regardless of whether we can build the logout URL, clear stored
        // tokens — we're logging the user out now.
        account.setOidcIdToken(null);
        account.setOidcRefreshTokenEnc(null);

        ApplicationSettings settings;
        Map<String, Object> discovery;
        try {
            settings = requireSettings();
            validateOidcConfig(settings);
            discovery = fetchDiscovery(settings.getOidcIssuerUrl());
        } catch (Exception e) {
            log.warn("Cannot build OIDC logout URL: {}", e.getMessage());
            return Optional.empty();
        }

        // Revoke the refresh token at the IdP (RFC 7009) before building the
        // logout URL. This is the piece that keeps a disabled / offboarded
        // user from silently re-authenticating: without revocation, the
        // refresh_token remains valid and a replayed browser session could
        // mint new ID tokens.
        if (encryptedRefresh != null) {
            try {
                String refreshToken = encryptionService.decrypt(encryptedRefresh);
                revokeToken(discovery, settings, refreshToken);
            } catch (Exception e) {
                log.warn("OIDC refresh token revocation failed (continuing with logout): {}", e.getMessage());
            }
        }

        String endSessionEndpoint = (String) discovery.get("end_session_endpoint");
        if (endSessionEndpoint == null) return Optional.empty();

        StringBuilder url = new StringBuilder(endSessionEndpoint);
        url.append(endSessionEndpoint.contains("?") ? "&" : "?");
        url.append("client_id=").append(urlEncode(settings.getOidcClientId()));
        if (idToken != null) {
            url.append("&id_token_hint=").append(urlEncode(idToken));
        }
        if (postLogoutRedirectUri != null && !postLogoutRedirectUri.isBlank()) {
            url.append("&post_logout_redirect_uri=").append(urlEncode(postLogoutRedirectUri));
        }
        return Optional.of(url.toString());
    }

    /**
     * Call the IdP's UserInfo endpoint with the access token and assert that
     * its {@code sub} claim matches the ID token's {@code sub}. OIDC Core
     * §5.3.2 requires this cross-check whenever UserInfo is consumed, and
     * it's cheap insurance even when we don't use UserInfo claims directly.
     * <p>
     * A missing userinfo_endpoint, missing access_token, or network failure
     * is logged but not fatal — some minimal IdPs don't support UserInfo.
     * A present-but-mismatched sub IS fatal.
     */
    private void verifyUserInfoSub(Map<String, Object> discovery, String accessToken, String expectedSub) {
        if (accessToken == null || accessToken.isBlank() || expectedSub == null) return;
        String userInfoEndpoint = (String) discovery.get("userinfo_endpoint");
        if (userInfoEndpoint == null) return;

        Map<String, Object> userInfo;
        try {
            userInfo = httpGetJsonWithBearer(userInfoEndpoint, accessToken);
        } catch (Exception e) {
            log.warn("OIDC UserInfo call failed (skipping cross-check): {}", e.getMessage());
            return;
        }

        Object userInfoSub = userInfo.get("sub");
        if (userInfoSub == null) {
            // Spec-mandated field — missing is an IdP bug, but we fail closed.
            throw new BadCredentialsException("UserInfo response is missing required 'sub' claim");
        }
        if (!expectedSub.equals(userInfoSub.toString())) {
            log.warn("OIDC UserInfo sub mismatch: id_token.sub={} vs userinfo.sub={}", expectedSub, userInfoSub);
            throw new BadCredentialsException("UserInfo sub claim does not match ID token sub");
        }
    }

    /**
     * RFC 7009 token revocation. Best-effort — we log and swallow failures
     * because revocation at logout is defense in depth, not a hard guarantee.
     */
    private void revokeToken(Map<String, Object> discovery, ApplicationSettings settings, String token) {
        String revocationEndpoint = (String) discovery.get("revocation_endpoint");
        if (revocationEndpoint == null) return;

        StringBuilder body = new StringBuilder();
        body.append("token=").append(urlEncode(token));
        body.append("&token_type_hint=refresh_token");
        body.append("&client_id=").append(urlEncode(settings.getOidcClientId()));
        if (settings.getOidcClientSecretEnc() != null) {
            String secret = encryptionService.decrypt(settings.getOidcClientSecretEnc());
            body.append("&client_secret=").append(urlEncode(secret));
        }
        try {
            UrlValidator.requireSafeUrl(revocationEndpoint);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(revocationEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // Per RFC 7009, the revocation endpoint responds 200 regardless
            // of whether the token was valid, to avoid leaking information.
            if (response.statusCode() >= 400) {
                log.warn("OIDC revocation returned HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("OIDC revocation request failed: {}", e.getMessage());
        }
    }

    // ── ID token validation using JJWT ───────────────────────────────────────

    private Claims validateIdToken(String idTokenStr, ApplicationSettings settings,
                                    Map<String, Object> discovery, String expectedNonce) {
        String jwksUri = (String) discovery.get("jwks_uri");
        if (jwksUri == null) {
            throw new IllegalStateException("OIDC discovery missing jwks_uri");
        }

        // Decode the JWT header first so we can validate the algorithm and
        // locate the signing key by kid BEFORE handing the token to JJWT.
        String[] parts = idTokenStr.split("\\.");
        if (parts.length != 3) {
            throw new BadCredentialsException("Malformed ID token");
        }
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        Map<String, Object> header;
        try {
            header = objectMapper.readValue(headerJson, new TypeReference<>() {});
        } catch (IOException e) {
            throw new BadCredentialsException("Malformed ID token header");
        }

        String alg = (String) header.get("alg");
        if (alg == null || !ALLOWED_ID_TOKEN_ALGORITHMS.contains(alg)) {
            throw new BadCredentialsException("Unsupported or missing ID token signing algorithm: " + alg);
        }

        String kid = (String) header.get("kid");

        // Look up signing key by kid. If not found, refetch the JWKS once to
        // handle key rotation; if still not found, fail. No fallback to
        // "first RSA key" — that could select the wrong key during rotation.
        Map<String, Object> matchingKey = findKeyByKid(fetchJwks(jwksUri, false), kid);
        if (matchingKey == null) {
            matchingKey = findKeyByKid(fetchJwks(jwksUri, true), kid);
        }
        if (matchingKey == null) {
            throw new BadCredentialsException("No JWKS key matches kid=" + kid);
        }

        PublicKey publicKey = buildRsaPublicKey(matchingKey);

        // Parse and verify the ID token using JJWT
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(settings.getOidcIssuerUrl())
                    .requireAudience(settings.getOidcClientId())
                    .build()
                    .parseSignedClaims(idTokenStr)
                    .getPayload();
        } catch (Exception e) {
            log.warn("OIDC ID token validation failed: {}", e.getMessage());
            throw new BadCredentialsException("Invalid ID token: " + e.getMessage());
        }

        // Verify nonce
        String tokenNonce = claims.get("nonce", String.class);
        if (!expectedNonce.equals(tokenNonce)) {
            throw new BadCredentialsException("ID token nonce mismatch");
        }

        return claims;
    }

    private Map<String, Object> findKeyByKid(Map<String, Object> jwks, String kid) {
        if (jwks == null || kid == null) return null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        if (keys == null) return null;
        for (Map<String, Object> key : keys) {
            if (kid.equals(key.get("kid"))) return key;
        }
        return null;
    }

    private Map<String, Object> fetchJwks(String jwksUri, boolean forceRefresh) {
        if (!forceRefresh && jwksCache != null && jwksUri.equals(jwksCacheUri)) {
            return jwksCache;
        }
        Map<String, Object> jwks = httpGetJson(jwksUri);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        if (keys == null || keys.isEmpty()) {
            throw new BadCredentialsException("OIDC JWKS contains no keys");
        }
        jwksCache = jwks;
        jwksCacheUri = jwksUri;
        return jwks;
    }

    private PublicKey buildRsaPublicKey(Map<String, Object> jwk) {
        try {
            String n = (String) jwk.get("n");
            String e = (String) jwk.get("e");
            if (n == null || e == null) {
                throw new BadCredentialsException("JWK missing 'n' or 'e' parameter");
            }

            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));

            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return factory.generatePublic(spec);
        } catch (BadCredentialsException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadCredentialsException("Failed to construct RSA public key from JWK", ex);
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> httpGetJson(String url) {
        UrlValidator.requireSafeUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP GET " + url + " returned " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch " + url + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> httpGetJsonWithBearer(String url, String bearerToken) {
        UrlValidator.requireSafeUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP GET " + url + " returned " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch " + url + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> httpPostForm(String url, String formBody) {
        UrlValidator.requireSafeUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OIDC token exchange failed: HTTP {} — {}", response.statusCode(), response.body());
                throw new BadCredentialsException("OIDC token exchange failed: HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (BadCredentialsException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OIDC token exchange request failed: " + e.getMessage(), e);
        }
    }

    // ── Discovery document ───────────────────────────────────────────────────

    private Map<String, Object> fetchDiscovery(String issuerUrl) {
        if (discoveryCache != null && issuerUrl.equals(discoveryIssuer)) {
            return discoveryCache;
        }

        String discoveryUrl = issuerUrl.endsWith("/")
                ? issuerUrl + ".well-known/openid-configuration"
                : issuerUrl + "/.well-known/openid-configuration";

        Map<String, Object> doc = httpGetJson(discoveryUrl);
        discoveryCache = doc;
        discoveryIssuer = issuerUrl;
        return doc;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ApplicationSettings requireSettings() {
        return settingsRepo.findFirstBy()
                .orElseThrow(() -> new BadCredentialsException("Application settings not configured"));
    }

    private void validateOidcConfig(ApplicationSettings settings) {
        if (settings.getOidcIssuerUrl() == null || settings.getOidcIssuerUrl().isBlank()) {
            throw new IllegalStateException("OIDC issuer URL not configured");
        }
        if (settings.getOidcClientId() == null || settings.getOidcClientId().isBlank()) {
            throw new IllegalStateException("OIDC client ID not configured");
        }
    }

    private String computeCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String generateRandomString(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
