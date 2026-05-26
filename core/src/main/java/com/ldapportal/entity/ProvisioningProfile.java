// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.UUID;

/**
 * A provisioning profile defines how users of a particular type are created,
 * managed, and governed within a directory connection.
 *
 * <p>Replaces the former {@link Realm} + {@link UserTemplate} combination,
 * unifying identity provisioning, access assignment, lifecycle policy,
 * approval workflow, and form layout into a single manageable unit.</p>
 */
@Entity
@Table(
    name = "provisioning_profiles",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_profile_directory_name",
        columnNames = {"directory_id", "name"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ProvisioningProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "directory_id", nullable = false)
    private DirectoryConnection directory;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    /** LDAP DN of the OU where new users are created. */
    @Column(name = "target_ou_dn", nullable = false, length = 500)
    private String targetOuDn;

    /** LDAP objectClasses applied to entries created with this profile. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "profile_object_classes",
        joinColumns = @JoinColumn(name = "profile_id")
    )
    @Column(name = "object_class_name")
    private List<String> objectClassNames = new ArrayList<>();

    /** The attribute used as the RDN when constructing the entry DN. */
    @Column(name = "rdn_attribute", nullable = false, length = 100)
    private String rdnAttribute;

    @Column(name = "show_dn_field", nullable = false)
    private boolean showDnField = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "self_registration_allowed", nullable = false)
    private boolean selfRegistrationAllowed = false;

    // ── Password generation settings ────────────────────────────────────────

    @Column(name = "password_length", nullable = false)
    private int passwordLength = 16;

    @Column(name = "password_uppercase", nullable = false)
    private boolean passwordUppercase = true;

    @Column(name = "password_lowercase", nullable = false)
    private boolean passwordLowercase = true;

    @Column(name = "password_digits", nullable = false)
    private boolean passwordDigits = true;

    @Column(name = "password_special", nullable = false)
    private boolean passwordSpecial = true;

    @Column(name = "password_special_chars", nullable = false, length = 50)
    private String passwordSpecialChars = "!@#$%^&*";

    @Column(name = "email_password_to_user", nullable = false)
    private boolean emailPasswordToUser = false;

    /** When true, this profile's group assignments are auto-included in every other profile in the same directory. */
    @Column(name = "auto_include_groups", nullable = false)
    private boolean autoIncludeGroups = false;

    /** When true, this profile opts out of receiving auto-included groups from other profiles. */
    @Column(name = "exclude_auto_includes", nullable = false)
    private boolean excludeAutoIncludes = false;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "profile_additional_profiles",
        joinColumns = @JoinColumn(name = "profile_id"),
        inverseJoinColumns = @JoinColumn(name = "additional_profile_id")
    )
    private Set<ProvisioningProfile> additionalProfiles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
