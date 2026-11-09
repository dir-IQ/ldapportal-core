// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link SyncVerifyEntry}: its set and position. */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SyncVerifyEntryId implements Serializable {
    private UUID syncSetId;
    private int seq;
}
