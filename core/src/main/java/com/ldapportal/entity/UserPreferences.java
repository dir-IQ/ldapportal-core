// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Single home for every per-account UI customization the user makes — theme,
 * display density, table column widths/visibility/sort, saved filter views,
 * search history, modal sizes, sidebar state, and anything a future feature
 * wants to remember. The frontend used to scatter these across browser
 * localStorage; they now live server-side so they follow the user across
 * browsers and devices.
 *
 * <p>The shape is a namespaced JSON document — {@code appearance}, {@code
 * tables}, {@code filters}, {@code search}, {@code modals}, {@code sidebar} —
 * stored as an opaque JSONB blob. The frontend owns the schema within each
 * namespace; the server validates only the top-level namespace set and the
 * document size, then persists. New preference types add keys, never columns
 * or migrations.</p>
 *
 * <p>Keyed on {@code account_id}: only real accounts (superadmin / admin)
 * persist customizations. Self-service LDAP principals — who have no
 * {@code accounts} row — do not.</p>
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
public class UserPreferences {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    /**
     * Namespaced preferences document. Never null at rest — defaults to an
     * empty object so a partial (merge-patch) write against a brand-new row
     * has something to merge into.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> document;

    /**
     * Optimistic-lock counter. Writes apply a server-side merge-patch into the
     * stored document, so two tabs editing <em>different</em> namespaces never
     * collide; this guards the narrower case of two writes racing on the
     * <em>same</em> namespace, where the loser retries on a fresh read rather
     * than silently clobbering.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
