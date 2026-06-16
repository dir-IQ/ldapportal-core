// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.converter;

import com.ldapportal.entity.enums.SuperadminPermission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link SuperadminPermission}.
 * Maps between the Java enum constant and the dot-notation DB value
 * (e.g. {@code MANAGE_APPLICATION_ACCOUNTS} ↔
 * {@code "superadmin.manage_application_accounts"}).
 * autoApply = true so it's picked up for all {@code SuperadminPermission}-typed
 * columns without explicit annotation.
 */
@Converter(autoApply = true)
public class SuperadminPermissionConverter implements AttributeConverter<SuperadminPermission, String> {

    @Override
    public String convertToDatabaseColumn(SuperadminPermission attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public SuperadminPermission convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SuperadminPermission.fromDbValue(dbData);
    }
}
