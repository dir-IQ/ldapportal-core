// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

/** Net membership change a group {@code modify} would apply: {@code +added / -removed}. */
public record LdifMemberDelta(int added, int removed) {}
