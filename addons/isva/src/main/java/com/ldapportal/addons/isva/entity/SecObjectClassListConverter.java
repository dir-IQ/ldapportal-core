// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@code TEXT} comma-separated list column to/from a
 * {@code List<String>} on the entity. Used for both
 * {@code secuser_object_classes} and {@code secuser_overlay_attributes}
 * — both hold LDAP objectClass / attribute names.
 *
 * <p>A delimited column rather than a child table is deliberate: this
 * is a single-row-per-directory config and the codebase keeps that
 * config flat. LDAP objectClass / attribute names are restricted to
 * letters, digits and hyphens, so a comma is an unambiguous,
 * never-occurring delimiter — no escaping needed.</p>
 *
 * <p>Not {@code autoApply} — applied explicitly via {@code @Convert}
 * on each field that uses it, so it can't accidentally catch other
 * {@code List<String>} columns a future migration might add.</p>
 */
@Converter
public class SecObjectClassListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return String.join(",", attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        List<String> out = new ArrayList<>();
        if (dbData == null || dbData.isBlank()) {
            return out;
        }
        for (String part : dbData.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
