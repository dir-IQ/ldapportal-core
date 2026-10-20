// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.util.StaticUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns planned target writes into displayable per-attribute changes for the
 * reconcile preview: what each attribute would lose and gain. Password values
 * are never shown, binary values are summarised by size, and each value list is
 * capped (a large group's {@code member} list would otherwise swamp the page).
 */
final class SyncPreviewValues {

    /** Values listed per side of an attribute change; the rest are counted. */
    static final int MAX_VALUES = 50;

    static final String REDACTED = "(redacted)";

    private static final Set<String> SECRET_ATTRIBUTES = Set.of(
            "userpassword", "authpassword", "unicodepwd", "pwdhistory");

    private SyncPreviewValues() {
    }

    /** Every attribute of a planned ADD, as values gained. */
    static List<SyncPreviewAttributeChange> forAdd(List<Attribute> attrs) {
        List<SyncPreviewAttributeChange> out = new ArrayList<>(attrs.size());
        for (Attribute a : attrs) {
            out.add(change(a.getName(), List.of(), values(a)));
        }
        return out;
    }

    /**
     * The attributes {@code mods} would touch on {@code before}, as values lost and
     * gained. The resulting entry is computed with the same modification semantics
     * the server applies, so a REPLACE shows exactly what it drops and adds.
     */
    static List<SyncPreviewAttributeChange> forModify(Entry before, List<Modification> mods) {
        Entry after;
        try {
            after = Entry.applyModifications(before, true, mods);
        } catch (LDAPException e) {
            // Lenient application doesn't reject value conflicts; anything else is
            // a malformed modification — show the raw operations instead.
            return forRawModifications(mods);
        }
        Map<String, String> touched = new LinkedHashMap<>();
        for (Modification m : mods) {
            touched.putIfAbsent(m.getAttributeName().toLowerCase(Locale.ROOT), m.getAttributeName());
        }
        List<SyncPreviewAttributeChange> out = new ArrayList<>(touched.size());
        for (String name : touched.values()) {
            List<String> was = values(before.getAttribute(name));
            List<String> now = values(after.getAttribute(name));
            List<String> removed = minus(was, now);
            List<String> added = minus(now, was);
            if (!removed.isEmpty() || !added.isEmpty()) {
                out.add(change(name, removed, added));
            }
        }
        return out;
    }

    private static List<SyncPreviewAttributeChange> forRawModifications(List<Modification> mods) {
        List<SyncPreviewAttributeChange> out = new ArrayList<>(mods.size());
        for (Modification m : mods) {
            List<String> vals = List.of(m.getValues());
            if (m.getModificationType() == ModificationType.DELETE) {
                out.add(change(m.getAttributeName(), vals, List.of()));
            } else {
                out.add(change(m.getAttributeName(), List.of(), vals));
            }
        }
        return out;
    }

    private static List<String> values(Attribute a) {
        if (a == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(a.size());
        for (byte[] raw : a.getValueByteArrays()) {
            out.add(StaticUtils.isValidUTF8(raw) ? StaticUtils.toUTF8String(raw) : binary(raw.length));
        }
        return out;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        Set<String> drop = new LinkedHashSet<>(b);
        List<String> out = new ArrayList<>();
        for (String v : a) {
            if (!drop.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static SyncPreviewAttributeChange change(String name, List<String> removed, List<String> added) {
        boolean secret = SECRET_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT));
        List<String> r = display(removed, secret);
        List<String> a = display(added, secret);
        return new SyncPreviewAttributeChange(name, r, a,
                removed.size() - r.size(), added.size() - a.size());
    }

    private static List<String> display(List<String> vals, boolean secret) {
        List<String> out = new ArrayList<>(Math.min(vals.size(), MAX_VALUES));
        for (String v : vals) {
            if (out.size() == MAX_VALUES) {
                break;
            }
            out.add(secret ? REDACTED : printable(v));
        }
        return out;
    }

    /** UTF-8 that isn't readable text (control bytes) is summarised like binary. */
    private static String printable(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return binary(v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        }
        return v;
    }

    private static String binary(int length) {
        return "(binary, " + length + " bytes)";
    }
}
