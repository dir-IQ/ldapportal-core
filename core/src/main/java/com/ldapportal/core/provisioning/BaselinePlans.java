// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for what a no-interceptor LDAP write
 * looks like — i.e. what {@code LdapUserService} / {@code LdapGroupService}
 * did before the {@link ProvisioningInterceptor} SPI existed.
 *
 * <p>Two callers:
 * <ol>
 *   <li>{@link ProvisioningInterceptorChain}, when no interceptor
 *       is registered: returns one of these plans directly.</li>
 *   <li>Concrete {@link ProvisioningInterceptor} implementations,
 *       for directories they don't specialise: delegate to the
 *       relevant factory below.</li>
 * </ol>
 *
 * <p>Centralising here means the baseline shape changes in exactly
 * one place when LDAPPortal's default behaviour evolves — and
 * every interceptor automatically picks up the change for its
 * non-specialised paths.</p>
 */
public final class BaselinePlans {

    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private BaselinePlans() {}

    /**
     * Single-step ADD that lands the user entry as the provisioning
     * profile declared it. Exact same shape as the pre-SPI
     * {@code LdapUserService.createUser} produced.
     */
    public static UserCreatePlan userCreate(UserCreatePayload payload) {
        List<Attribute> attrs = new ArrayList<>();
        payload.attributes().forEach((name, values) ->
                attrs.add(new Attribute(name, values.toArray(new String[0]))));
        return UserCreatePlan.singleStep(AddStep.of(payload.dn(), attrs));
    }

    /**
     * Single-step DEL. The pre-SPI default — no soft-disable
     * machinery, no two-entry fan-out.
     */
    public static DeletePlan userDelete(String dn) {
        return DeletePlan.singleStep(DeleteStep.of(dn));
    }

    /**
     * Single-step MODIFY replacing the password attribute. For
     * Active Directory this is {@code unicodePwd} with UTF-16LE
     * encoding and quote-wrapping; for every other directory it's
     * a straight {@code userPassword} REPLACE.
     */
    public static PasswordPlan passwordSet(DirectoryConnection dir,
                                            String dn,
                                            PasswordSetPayload payload) {
        Modification mod;
        if (dir.getDirectoryType() == DirectoryType.ACTIVE_DIRECTORY) {
            // AD demands the password be quoted then encoded as
            // UTF-16LE; otherwise the bind silently succeeds without
            // changing the password. The bug is famous enough that
            // every AD admin has hit it at least once.
            String quoted = "\"" + payload.newPassword() + "\"";
            byte[] encoded;
            try {
                encoded = quoted.getBytes(UTF_16LE);
            } catch (Exception e) {
                throw new LdapOperationException(
                        "Failed to encode password for Active Directory", e);
            }
            mod = new Modification(ModificationType.REPLACE, "unicodePwd", encoded);
        } else {
            mod = new Modification(ModificationType.REPLACE, "userPassword",
                    payload.newPassword());
        }
        return PasswordPlan.singleStep(ModifyStep.of(dn, List.of(mod)));
    }

    /**
     * Single-step MODIFY ADD-ing the membership. Baseline never
     * refuses; refusal is a feature of vendor-aware interceptors
     * (e.g. ISVA refusing groups without the {@code secGroup}
     * overlay).
     */
    public static GroupMemberPlan groupMembership(String groupDn,
                                                   String memberAttribute,
                                                   String memberValue) {
        Modification mod = new Modification(ModificationType.ADD,
                memberAttribute, memberValue);
        return GroupMemberPlan.proceed(ModifyStep.of(groupDn, List.of(mod)));
    }

    /**
     * Helper: convert the provisioning-payload attribute map shape
     * (LDAPPortal's wire format) to UnboundID's {@code Attribute}
     * list shape. Public because vendor-aware interceptors that
     * augment the payload then build a custom plan need it too.
     */
    public static List<Attribute> attributesFromMap(Map<String, List<String>> map) {
        List<Attribute> result = new ArrayList<>(map.size());
        map.forEach((name, values) ->
                result.add(new Attribute(name, values.toArray(new String[0]))));
        return result;
    }
}
