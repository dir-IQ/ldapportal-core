// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD {@code objectGUID} is 16 mixed-endian bytes. The index must key on the
 * canonical GUID string (stable, printable, collision-free) and the identity
 * search must match with the original bytes, otherwise a not-seen row or a
 * pre-move-DN trigger resolves to "absent" and deletes a live target entry.
 */
class ObjectGuidIdentityStrategyTest {

    private final ObjectGuidIdentityStrategy strategy = new ObjectGuidIdentityStrategy();

    // The well-known example from Microsoft's docs: bytes on the wire vs display form.
    private static final byte[] WIRE = {
            (byte) 0x33, (byte) 0x22, (byte) 0x11, (byte) 0x00,
            (byte) 0x55, (byte) 0x44,
            (byte) 0x77, (byte) 0x66,
            (byte) 0x88, (byte) 0x99,
            (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff};
    private static final String CANONICAL = "00112233-4455-6677-8899-aabbccddeeff";

    @Test
    void extract_decodesMixedEndianBytesToCanonicalString() {
        Entry e = new Entry("cn=x,dc=test", new Attribute("objectGUID", WIRE));

        assertThat(strategy.extract(e, "objectGUID")).isEqualTo(CANONICAL);
        assertThat(strategy.extract(e)).isEqualTo(CANONICAL);
    }

    @Test
    void identityFilter_matchesWithTheOriginalBytes_notTheString() throws Exception {
        Filter f = strategy.identityFilter("objectGUID", CANONICAL);

        assertThat(f.getAttributeName()).isEqualTo("objectGUID");
        assertThat(f.getAssertionValueBytes()).isEqualTo(WIRE);
        assertThat(f.matchesEntry(new Entry("cn=x,dc=test", new Attribute("objectGUID", WIRE)))).isTrue();
    }

    @Test
    void canonicalRoundTrip_isLossless() {
        assertThat(ObjectGuidIdentityStrategy.fromCanonical(ObjectGuidIdentityStrategy.toCanonical(WIRE)))
                .isEqualTo(WIRE);
    }

    @Test
    void distinctGuidsThatCollideAsText_stayDistinct() {
        // Two byte strings that are both invalid UTF-8 collapse to the same
        // replacement-character text; canonical GUIDs keep them apart.
        byte[] a = WIRE.clone();
        byte[] b = WIRE.clone();
        b[15] = (byte) 0xfe;
        assertThat(ObjectGuidIdentityStrategy.toCanonical(a))
                .isNotEqualTo(ObjectGuidIdentityStrategy.toCanonical(b));
    }

    @Test
    void otherIdentityAttribute_onAdSet_keepsStringHandling() {
        Entry e = new Entry("cn=x,dc=test", new Attribute("employeeID", " E-1001 "));

        assertThat(strategy.extract(e, "employeeID")).isEqualTo("e-1001");
        assertThat(strategy.identityFilter("employeeID", "e-1001").getAssertionValue()).isEqualTo("e-1001");
    }
}
