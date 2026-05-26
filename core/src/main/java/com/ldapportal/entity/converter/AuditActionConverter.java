// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.converter;

import com.ldapportal.entity.enums.AuditAction;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class AuditActionConverter implements AttributeConverter<AuditAction, String> {

    @Override
    public String convertToDatabaseColumn(AuditAction attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public AuditAction convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AuditAction.fromDbValue(dbData);
    }
}
