// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite primary key for {@link Membership}: one row per identity per sync
 * set.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MembershipId implements Serializable {

    private UUID syncSetId;
    private String identity;
}
