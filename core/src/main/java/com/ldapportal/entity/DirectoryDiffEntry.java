// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.DirectoryDiffCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One result of a directory diff run: an entry on one side, or a pair of
 * entries, and the group it landed in. Written and read in bulk by
 * {@code DirectoryDiffEntryStore}; mapped here so the schema is validated.
 */
@Entity
@Table(name = "directory_diff_entry")
@IdClass(DirectoryDiffEntryId.class)
@Getter
@Setter
@NoArgsConstructor
public class DirectoryDiffEntry {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    /** Position in the run, from 1. */
    @Id
    @Column(name = "seq")
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private DirectoryDiffCategory category;

    @Column(name = "dn_a", columnDefinition = "TEXT")
    private String dnA;

    @Column(name = "dn_b", columnDefinition = "TEXT")
    private String dnB;

    /** The attributes that differ, as {@code ,mail,title,} (filtered case-insensitively), for DIFFERENT. */
    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes;

    /** Why an entry has no match despite looking like it should (e.g. a pairing value two entries share). */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** For DIFFERENT, each differing attribute's values per side; the names of the equal ones. JSON. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;
}
