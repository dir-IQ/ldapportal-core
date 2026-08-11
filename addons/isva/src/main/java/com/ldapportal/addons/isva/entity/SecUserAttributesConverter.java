// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Maps the {@code secuser_attributes} {@code TEXT} column to/from a
 * {@code List<SecUserAttribute>} on the entity, serialized as a JSON
 * array. Unlike the flat objectClass/attribute-name lists (which use
 * {@link SecObjectClassListConverter}), each element here is a small
 * object ({@code name}, {@code enabled}, {@code valueKind},
 * {@code value}) — JSON is the natural fit and keeps the config a
 * single flat row per directory.
 *
 * <p>A {@code null} / empty model column stores {@code null}: that's the
 * "not migrated yet" state, and the plan builders derive an equivalent
 * model from the legacy value fields on the fly, so behaviour is
 * unchanged until an explicit model is saved.</p>
 *
 * <p>Not {@code autoApply} — applied explicitly via {@code @Convert} so
 * it can't accidentally catch another {@code List} column.</p>
 */
@Converter
public class SecUserAttributesConverter
        implements AttributeConverter<List<SecUserAttribute>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Tolerate columns written by a newer model shape (extra keys)
            // rather than failing the whole read.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<SecUserAttribute>> TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<SecUserAttribute> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize secUser attribute model", e);
        }
    }

    @Override
    public List<SecUserAttribute> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize secUser attribute model: " + dbData, e);
        }
    }
}
