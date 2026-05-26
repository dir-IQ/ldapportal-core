// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.ApiTokenService;
import com.ldapportal.auth.AuthContextHelper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.apitoken.ApiTokenCreateResponse;
import com.ldapportal.dto.apitoken.ApiTokenResponse;
import com.ldapportal.dto.apitoken.CreateApiTokenRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public ApiTokenResponse get(@PathVariable UUID id) {
        return ApiTokenResponse.from(service.get(id));
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
