// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import java.util.List;
import java.util.Map;

/**
 * A directory entry as read by reconciliation: its DN plus its
 * attribute values. Attribute names are server-returned (case varies);
 * the differ compares them case-insensitively.
 */
public record ReconEntry(String dn, Map<String, List<String>> attributes) {}
