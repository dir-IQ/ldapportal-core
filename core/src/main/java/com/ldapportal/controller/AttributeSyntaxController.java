// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.dto.ldap.AttributeSyntaxHints;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the built-in attribute-syntax hints the server enforces on write
 * (DN / email / boolean) so the admin create/edit forms can mirror those checks
 * for instant field-level feedback, keyed to the same source of truth the
 * backend validates against ({@code WellKnownAttributes} + the profile
 * {@code inputType} mapping). The payload is small, static, and directory-
 * agnostic.
 *
 * <pre>
 *   GET /api/v1/attribute-syntax
 *     → {"wellKnownAttributes": {"manager": "DN", "mail": "EMAIL", ...},
 *        "inputTypeSyntax":      {"DN_LOOKUP": "DN", "BOOLEAN": "BOOLEAN"}}
 * </pre>
 *
 * <p>Secured by the global {@code /api/v1/**} rule (SUPERADMIN/ADMIN) — it is
 * metadata for the admin forms.</p>
 */
@RestController
@RequestMapping("/api/v1/attribute-syntax")
@RequiredArgsConstructor
public class AttributeSyntaxController {

    @GetMapping
    public AttributeSyntaxHints attributeSyntax() {
        return AttributeSyntaxHints.current();
    }
}
