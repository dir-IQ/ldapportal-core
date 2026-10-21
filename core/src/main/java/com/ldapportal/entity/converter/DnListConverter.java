// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JPA converter for a short list of DNs stored one per line (DNs contain
 * commas, so {@link ObjectClassListConverter}'s comma form doesn't fit).
 *
 * <p>Round-trips defensively: blank lines and surrounding whitespace are
 * dropped, a null/blank column becomes an empty list, and an empty list is
 * stored as {@code null}.</p>
 */
@Converter
public class DnListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        String joined = attribute.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
