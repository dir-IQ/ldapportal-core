// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TargetEntryDifferTest {

    @Test
    void protectedAttrs_areNeverDeletedFromTarget() {
        Entry target = new Entry("uid=alice,ou=Users,dc=dst",
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"),
                new Attribute("userPassword", "{SSHA}server-hash"),
                new Attribute("description", "stale"));
        // Desired keeps uid + cn. userPassword is excluded (protected); description
        // is no longer desired and should be removed.
        List<Attribute> desired = List.of(
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"));

        List<Modification> mods =
                TargetEntryDiffer.diff(target, desired, Set.of("userpassword"));

        assertThat(mods).anySatisfy(m -> {
            assertThat(m.getModificationType()).isEqualTo(ModificationType.DELETE);
            assertThat(m.getAttributeName()).isEqualTo("description");
        });
        // The protected attribute is left untouched — never deleted.
        assertThat(mods).noneSatisfy(m ->
                assertThat(m.getAttributeName()).isEqualToIgnoringCase("userPassword"));
    }
}
