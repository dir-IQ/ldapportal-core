// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.converter;

import com.ldapportal.entity.enums.FeatureKey;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link FeatureKey}.
 * Maps between the Java enum constant and the dot-notation DB value
 * (e.g. {@code USER_CREATE} ↔ {@code "user.create"}).
 * autoApply = true means this converter is picked up automatically for all
 * {@code FeatureKey}-typed columns without explicit annotation.
 */
@Converter(autoApply = true)
public class FeatureKeyConverter implements AttributeConverter<FeatureKey, String> {

    @Override
    public String convertToDatabaseColumn(FeatureKey attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public FeatureKey convertToEntityAttribute(String dbData) {
        return dbData == null ? null : FeatureKey.fromDbValue(dbData);
    }
}
