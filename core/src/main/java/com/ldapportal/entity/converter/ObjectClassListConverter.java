// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JPA converter for a small, unordered set of LDAP objectClass names
 * stored as a single comma-delimited column (e.g.
 * {@code "inetOrgPerson,organizationalPerson,person"}).
 *
 * <p>Not {@code autoApply} — {@code List<String>} is too generic to bind
 * globally, so the directory-connection objectClass columns opt in with
 * {@code @Convert(converter = ObjectClassListConverter.class)}.</p>
 *
 * <p>Round-trips defensively: blanks and surrounding whitespace are
 * dropped, a null/blank column becomes an empty list (callers resolve
 * vendor defaults from there), and an empty list becomes {@code null} so
 * the "unset → use vendor default" semantics survive a save/load cycle.</p>
 */
@Converter
public class ObjectClassListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        String joined = attribute.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
