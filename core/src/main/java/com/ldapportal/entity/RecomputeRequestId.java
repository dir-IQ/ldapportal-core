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
 * Composite primary key for {@link RecomputeRequest}: the PK gives free dedup
 * for the coalescing trigger queue.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RecomputeRequestId implements Serializable {

    private UUID syncSetId;
    private String requestKey;
}
