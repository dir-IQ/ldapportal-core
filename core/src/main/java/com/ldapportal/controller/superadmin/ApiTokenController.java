// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.ApiTokenService;
import com.ldapportal.auth.AuthContextHelper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.apitoken.ApiTokenCreateResponse;
import com.ldapportal.dto.apitoken.ApiTokenResponse;
import com.ldapportal.dto.apitoken.CreateApiTokenRequest;
import com.ldapportal.dto.apitoken.UpsertApiTokenRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.web.ETagSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD + rotate for API tokens. Entire class is superadmin-only (also enforced
 * by the URL pattern in {@code SecurityConfig}; class-level {@code @PreAuthorize}
 * is defense-in-depth).
 *
 * <p>Mutating endpoints (create, rotate, revoke) reject callers authenticated
 * via an API token — tokens cannot mint or revoke other tokens. Read endpoints
 * (list, get) allow token callers for self-introspection.</p>
 */
@RestController
@RequestMapping("/api/v1/superadmin/api-tokens")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService service;
    private final AccountRepository accountRepository;

    @GetMapping
    public List<ApiTokenResponse> list(
            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return service.list(includeRevoked).stream()
                .map(ApiTokenResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiTokenResponse> get(@PathVariable UUID id) {
        ApiTokenResponse resp = ApiTokenResponse.from(service.get(id));
        return ResponseEntity.ok().eTag(ETagSupport.format(resp.version())).body(resp);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenCreateResponse create(
            @Valid @RequestBody CreateApiTokenRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        Account creator = accountRepository.findById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("Account", principal.id()));
        ApiTokenService.CreateResult result = service.create(
                req.name(), req.description(), req.expiresAt(), creator);
        return new ApiTokenCreateResponse(
                ApiTokenResponse.from(result.token()),
                result.plaintext());
    }

    /**
     * Idempotent create-or-update of a token keyed by its stable name, for IaC
     * automation. <strong>201</strong> with the one-time plaintext when the
     * named token is first created; <strong>200</strong> with a {@code null}
     * plaintext when an existing token's metadata is updated in place (the
     * secret is never re-minted here — rotation is the explicit
     * {@code /{id}/rotate} verb). A name shared by more than one active token
     * is ambiguous and returns 409. Like create/rotate/revoke, an API-token
     * caller is rejected — tokens cannot manage other tokens.
     *
     * <p>An optional {@code If-Match} header makes the update-path apply
     * conditional on the token still being at the version the caller last saw
     * (412 on mismatch); it is ignored when the apply mints a new token. The
     * response carries the token's version as an {@code ETag}.</p>
     */
    @PutMapping("/by-name/{name}")
    public ResponseEntity<ApiTokenCreateResponse> upsertByName(
            @PathVariable String name,
            @Valid @RequestBody UpsertApiTokenRequest req,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        Account creator = accountRepository.findById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("Account", principal.id()));
        ApiTokenService.UpsertResult result = service.upsertByName(
                name, req.description(), req.expiresAt(), creator, principal,
                ETagSupport.parseIfMatch(ifMatch));
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ETagSupport.format(result.token().getVersion()))
                .body(new ApiTokenCreateResponse(
                        ApiTokenResponse.from(result.token()), result.plaintext()));
    }

    @PostMapping("/{id}/rotate")
    public ApiTokenCreateResponse rotate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        ApiTokenService.CreateResult result = service.rotate(id, principal);
        return new ApiTokenCreateResponse(
                ApiTokenResponse.from(result.token()),
                result.plaintext());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id,
                       @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        service.revoke(id, principal);
    }

    /**
     * Policy: a request authenticated via an API token cannot mint, rotate,
     * or revoke other tokens — eliminates the token-self-replication
     * escalation path. Read endpoints (list, get) do allow token callers.
     */
    private static void rejectApiTokenCaller() {
        if (AuthContextHelper.currentApiToken().isPresent()) {
            throw new AccessDeniedException(
                    "API tokens cannot create, rotate, or revoke other API tokens");
        }
    }
}
