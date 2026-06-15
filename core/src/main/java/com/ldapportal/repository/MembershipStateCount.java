// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.enums.MembershipState;

import java.util.UUID;

/**
 * Aggregate projection: how many membership rows a sync set holds in a given
 * state. Powers the at-a-glance health rollup (failed / review / applied counts)
 * on the Directory Sync links and sets, without loading the rows themselves.
 */
public interface MembershipStateCount {
    UUID getSyncSetId();

    MembershipState getState();

    long getCnt();
}
