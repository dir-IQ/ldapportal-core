// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link SyncVerifyHeld}: its set and position. */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SyncVerifyHeldId implements Serializable {
    private UUID syncSetId;
    private int seq;
}
