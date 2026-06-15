// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes;
import com.ldapportal.ldap.LdapSchemaService.SchemaListItem;
import com.ldapportal.service.LdapOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Exposes the LDAP schema (objectClasses and attributeTypes) for a directory.
 *
 * <pre>
 *   GET /api/directories/{directoryId}/schema/object-classes
 *   GET /api/directories/{directoryId}/schema/object-classes/{name}
 *   GET /api/directories/{directoryId}/schema/attribute-types
 *   GET /api/directories/{directoryId}/schema/attribute-types/{name}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final LdapOperationService service;

    @GetMapping("/object-classes")
    @RequiresFeature(FeatureKey.SCHEMA_READ)
    public List<SchemaListItem> listObjectClasses(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getObjectClassNames(directoryId, principal);
    }

    @GetMapping("/object-classes/{name}")
    @RequiresFeature(FeatureKey.SCHEMA_READ)
    public ObjectClassAttributes getObjectClass(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable String name,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getObjectClassAttributes(directoryId, principal, name);
    }

    @GetMapping("/object-classes/bulk")
    @RequiresFeature(FeatureKey.SCHEMA_READ)
    public ObjectClassAttributes getObjectClassesBulk(
            @DirectoryId @PathVariable UUID directoryId,
            @RequestParam List<String> names,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getObjectClassAttributesBulk(directoryId, principal, names);
    }

    @GetMapping("/attribute-types")
    @RequiresFeature(FeatureKey.SCHEMA_READ)
    public List<AttributeTypeInfo> listAttributeTypes(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getAttributeTypeNames(directoryId, principal);
    }

    @GetMapping("/attribute-types/{name}")
    @RequiresFeature(FeatureKey.SCHEMA_READ)
    public AttributeTypeInfo getAttributeType(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable String name,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getAttributeTypeInfo(directoryId, principal, name);
    }
}
