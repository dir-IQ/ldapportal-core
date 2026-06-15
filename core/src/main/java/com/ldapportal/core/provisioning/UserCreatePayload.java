// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;
import java.util.Map;

/**
 * Inputs to {@link ProvisioningInterceptor#planUserCreate}. Mirrors
 * the signature of {@code LdapUserService.createUser} — the
 * "demographic" attributes the operator typed plus the target DN
 * those attributes will be written under.
 *
 * <p>An interceptor that wants to add ISVA-style {@code sec*}
 * attributes does so by augmenting the {@code attributes} map in
 * its returned plan; the payload itself isn't mutated.</p>
 *
 * <p>Per-operation context (the resolved provisioning-profile id and
 * friends) rides alongside the payload as a separate
 * {@link ProvisioningContext} argument rather than as a payload
 * field — so the payload stays a pure description of the entry being
 * written and the context can grow without re-touching every
 * payload.</p>
 */
public record UserCreatePayload(
        String dn,
        Map<String, List<String>> attributes) {

    public UserCreatePayload {
        // Defensive immutability for the nested collections.
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static UserCreatePayload of(String dn, Map<String, List<String>> attributes) {
        return new UserCreatePayload(dn, attributes);
    }
}
