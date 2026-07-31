// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.PasswordDisposition;
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

    /**
     * Stable, globally-unique IaC external key (immutable). This is the key
     * config-as-code and admin profile-role references address a profile by,
     * so it survives a fresh install where the UUID would not. The service sets
     * a clean, collision-checked slug on create; {@link #ensureSlug()} is a
     * safety net for any persist path that didn't (clone, tests).
     */
    @Column(nullable = false, updatable = false, length = 100)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    /**
     * Optional UI theme colour for this profile, stored as a {@code #RRGGBB}
     * hex string (e.g. {@code #2563eb}). Null/unset means the profile has no
     * theme and the admin user/group screens render their default header
     * styling. Purely presentational — it does not affect provisioning.
     */
    @Column(name = "theme_color", length = 7)
    private String themeColor;

    /** LDAP DN of the OU where new users are created. */
    @Column(name = "target_user_dn", nullable = false, length = 500)
    private String targetUserDn;

    /**
     * LDAP DN of the container under which this profile's groups live.
     * Lets groups be administered from a subtree separate from users;
     * defaults to {@link #targetUserDn} when an admin doesn't set a
     * distinct value (and is backfilled to it for pre-existing profiles).
     */
    @Column(name = "target_group_dn", nullable = false, length = 500)
    private String targetGroupDn;

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

    /**
     * Optional template for the new-user DN, evaluated as a {@code ${attr}}
     * expression by the same engine that drives attribute computed-expressions
     * (e.g. {@code uid=${uid},ou=people,dc=example,dc=com}). When null/blank the
     * DN defaults to {@code <rdnAttribute>=<rdnValue>,<targetUserDn>}. The admin
     * create form seeds its editable DN field from this; whatever DN is finally
     * submitted is validated to remain within {@link #targetUserDn}.
     */
    @Column(name = "dn_template", length = 500)
    private String dnTemplate;

    /**
     * Layout of the read-only DN field on the create form (only when
     * {@link #showDnField} is on). All nullable; null reproduces the historical
     * default — the DN sits immediately after the RDN at 2/3 width.
     * {@code dnColumnSpan} is a 1–6 grid span; {@code dnSectionName} +
     * {@code dnDisplayOrder} position the DN among the form's fields, letting an
     * admin move and resize it like any other field in the layout designer.
     */
    @Column(name = "dn_column_span")
    private Integer dnColumnSpan;

    @Column(name = "dn_section_name", length = 100)
    private String dnSectionName;

    @Column(name = "dn_display_order")
    private Integer dnDisplayOrder;

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

    /**
     * How this profile sources and handles a new user's password. Defaults to
     * {@link PasswordDisposition#OPERATOR_ENTERED}; the {@code GENERATED_*}
     * modes have the server generate the value at create time (see
     * {@link PasswordDisposition}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "password_disposition", nullable = false, length = 32)
    private PasswordDisposition passwordDisposition = PasswordDisposition.OPERATOR_ENTERED;

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

    /**
     * Optimistic-lock counter. The update path replaces attribute configs
     * and group assignments wholesale (delete-then-reinsert), so without
     * this column two concurrent superadmin edits would silently lose
     * whichever's reinsert ran first. Hibernate raises
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * when the version moves under us, which GlobalExceptionHandler maps
     * to 409 Conflict.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Safety net guaranteeing the NOT NULL slug invariant for any persist path
     * that didn't set one explicitly (the service sets a clean, collision-checked
     * slug up front; this covers clones and tests). Derives from {@code name} and
     * appends a short random suffix so an auto-generated slug can't collide with
     * the unique index.
     */
    @PrePersist
    void ensureSlug() {
        if (slug == null || slug.isBlank()) {
            String base = name == null ? "" : name
                    .trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-+)|(-+$)", "");
            if (base.isEmpty()) base = "profile";
            if (base.length() > 80) base = base.substring(0, 80).replaceAll("-+$", "");
            slug = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
