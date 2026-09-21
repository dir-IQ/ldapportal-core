// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identity via Active Directory's {@code objectGUID}. AD returns it as a 16-byte
 * binary value (never {@code objectSID}, which changes on domain migration), in
 * Microsoft's mixed-endian GUID layout: the first three fields are little-endian,
 * the last eight bytes are in order.
 *
 * <p>The index keys on the canonical lowercase {@code 8-4-4-4-12} string, which is
 * also what the anchor attribute carries and what operators see. Searching the
 * source by identity converts the string back to the 16 bytes and matches with a
 * binary equality filter — a string filter over the raw bytes never matches, and
 * decoding the bytes as text (the previous behaviour) is lossy, so two GUIDs could
 * collide on one index row. Any other attribute configured as the identity key
 * on an AD set keeps the default string handling.
 */
@Component
public class ObjectGuidIdentityStrategy implements IdentityStrategy {

    static final String OBJECT_GUID = "objectGUID";

    private static final Pattern CANONICAL = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Override
    public boolean supports(DirectoryType type) {
        return type == DirectoryType.ACTIVE_DIRECTORY;
    }

    @Override
    public String identityAttribute() {
        return OBJECT_GUID;
    }

    @Override
    public String extract(Entry entry, String attribute) {
        if (entry == null || attribute == null) {
            return null;
        }
        if (!OBJECT_GUID.equalsIgnoreCase(attribute)) {
            return normalize(entry.getAttributeValue(attribute));
        }
        byte[] raw = entry.getAttributeValueBytes(attribute);
        if (raw == null) {
            return null;
        }
        if (raw.length == 16) {
            return toCanonical(raw);
        }
        // Not a binary GUID (e.g. a test fixture or a proxy that already stringified it).
        return normalize(entry.getAttributeValue(attribute));
    }

    @Override
    public Filter identityFilter(String attribute, String identity) {
        if (OBJECT_GUID.equalsIgnoreCase(attribute) && isCanonical(identity)) {
            return Filter.createEqualityFilter(attribute, fromCanonical(identity));
        }
        return Filter.createEqualityFilter(attribute, identity);
    }

    @Override
    public String normalize(String rawValue) {
        return rawValue == null ? null : rawValue.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code true} when {@code value} is already a canonical lowercase GUID string. */
    public static boolean isCanonical(String value) {
        return value != null && CANONICAL.matcher(value).matches();
    }

    /** The 16 AD bytes as a canonical lowercase GUID string (mixed-endian aware). */
    public static String toCanonical(byte[] b) {
        if (b == null || b.length != 16) {
            throw new IllegalArgumentException("objectGUID must be 16 bytes");
        }
        return String.format(Locale.ROOT,
                "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                b[3], b[2], b[1], b[0],
                b[5], b[4],
                b[7], b[6],
                b[8], b[9],
                b[10], b[11], b[12], b[13], b[14], b[15]);
    }

    /** The AD byte layout for a canonical GUID string (inverse of {@link #toCanonical}). */
    public static byte[] fromCanonical(String guid) {
        if (!isCanonical(guid)) {
            throw new IllegalArgumentException("not a canonical GUID: " + guid);
        }
        String hex = guid.replace("-", "");
        byte[] straight = new byte[16];
        for (int i = 0; i < 16; i++) {
            straight[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] ad = new byte[16];
        ad[0] = straight[3];
        ad[1] = straight[2];
        ad[2] = straight[1];
        ad[3] = straight[0];
        ad[4] = straight[5];
        ad[5] = straight[4];
        ad[6] = straight[7];
        ad[7] = straight[6];
        System.arraycopy(straight, 8, ad, 8, 8);
        return ad;
    }
}
