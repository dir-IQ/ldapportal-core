// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Composite primary key for {@link DirectoryDiffEntry}: a run and the entry's position in it. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DirectoryDiffEntryId implements Serializable {

    private UUID runId;
    private int seq;
}
