// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.InputType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Defines how a single LDAP attribute within a {@link ProvisioningProfile}
 * is presented and validated in user creation/edit forms.
 *
 * <p>Replaces {@link UserTemplateAttributeConfig} with additional fields
 * for default values, computed expressions, and validation rules.</p>
 */
@Entity
@Table(
    name = "profile_attribute_configs",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_profile_attribute",
        columnNames = {"profile_id", "attribute_name"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ProfileAttributeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProvisioningProfile profile;

    @Column(name = "attribute_name", nullable = false, length = 100)
    private String attributeName;

    @Column(name = "custom_label")
    private String customLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 20)
    private InputType inputType = InputType.TEXT;

    @Column(name = "required_on_create", nullable = false)
    private boolean requiredOnCreate = false;

    @Column(name = "editable_on_create", nullable = false)
    private boolean editableOnCreate = true;

    @Column(name = "editable_on_update", nullable = false)
    private boolean editableOnUpdate = true;

    @Column(name = "self_service_edit", nullable = false)
    private boolean selfServiceEdit = false;

    @Column(name = "self_registration_edit", nullable = false)
    private boolean selfRegistrationEdit = false;

    /** Static default value applied when the attribute is not supplied. */
    @Column(name = "default_value", length = 500)
    private String defaultValue;

    /** Expression using ${attributeName} interpolation, evaluated at creation time. */
    @Column(name = "computed_expression", length = 500)
    private String computedExpression;

    @Column(name = "validation_regex", length = 500)
    private String validationRegex;

    @Column(name = "validation_message")
    private String validationMessage;

    /** JSON array of allowed values for SELECT input type. */
    @Column(name = "allowed_values", columnDefinition = "TEXT")
    private String allowedValues;

    @Column(name = "min_length")
    private Integer minLength;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "section_name", length = 100)
    private String sectionName;

    /**
     * Width of this attribute in the 6-column user-form grid.
     * Default {@code 3} → two-column layout (two attributes side-by-side).
     * Set to {@code 2} for three-up rows of short attributes
     * (employeeNumber, country code, etc), or {@code 6} for full-width
     * (descriptions, postal address). Widgets that structurally need
     * room (PASSWORD, TEXTAREA, MULTI_VALUE, DN_LOOKUP) are forced
     * full-width by the frontend regardless of this value.
     */
    @Column(name = "column_span", nullable = false)
    private int columnSpan = 3;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(nullable = false)
    private boolean hidden = false;

    // ── Registration-specific layout ─────────────────────────────────────────

    @Column(name = "registration_section_name", length = 100)
    private String registrationSectionName;

    @Column(name = "registration_column_span")
    private Integer registrationColumnSpan;

    @Column(name = "registration_display_order")
    private Integer registrationDisplayOrder;

    // ── Self-service-specific layout ─────────────────────────────────────────

    @Column(name = "self_service_section_name", length = 100)
    private String selfServiceSectionName;

    @Column(name = "self_service_column_span")
    private Integer selfServiceColumnSpan;

    @Column(name = "self_service_display_order")
    private Integer selfServiceDisplayOrder;
}
