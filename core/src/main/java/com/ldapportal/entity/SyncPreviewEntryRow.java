// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.dto.sync.SyncPreviewAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.util.UUID;

/**
 * One planned entry of a {@link SyncPreviewRun}, in display order. Rows are
 * only ever inserted (a new run gets a new {@code runId}), so a constructed row
 * reports itself new and saving skips the select-before-insert; a loaded or
 * saved one doesn't (Spring Data won't delete an entity that claims to be new).
 */
@Entity
@Table(name = "sync_preview_entry")
@IdClass(SyncPreviewEntryRow.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class SyncPreviewEntryRow implements Persistable<SyncPreviewEntryRow.Key> {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Id
    @Column(name = "ordinal")
    private int ordinal;

    @Column(name = "sync_set_id", nullable = false)
    private UUID syncSetId;

    @Column(name = "identity", nullable = false, columnDefinition = "TEXT")
    private String identity;

    @Column(name = "source_dn", columnDefinition = "TEXT")
    private String sourceDn;

    @Column(name = "target_dn", columnDefinition = "TEXT")
    private String targetDn;

    @Column(name = "previous_target_dn", columnDefinition = "TEXT")
    private String previousTargetDn;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private SyncPreviewAction action;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** Listed by the "changes only" view. */
    @Column(name = "actionable", nullable = false)
    private boolean actionable;

    /** The attribute changes as JSON (secret values already redacted). */
    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes;

    @Override
    @Transient
    public Key getId() {
        return new Key(runId, ordinal);
    }

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean fresh = true;

    @Override
    @Transient
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }

    /** Composite key: one row per position per run. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID runId;
        private int ordinal;
    }
}
