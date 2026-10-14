// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

/**
 * An attribute a group-import row changes on an existing group: the value it
 * has now and the value the row sets. Only attributes the row provides and
 * that differ from the group's current value are listed.
 *
 * @param attribute    the LDAP attribute, e.g. {@code description} or {@code owner}
 * @param currentValue the group's value(s) today, joined with {@code |};
 *                     {@code null} when the attribute isn't set
 * @param newValue     the value from the CSV
 */
public record GroupAttributeChange(String attribute, String currentValue, String newValue) {
}
