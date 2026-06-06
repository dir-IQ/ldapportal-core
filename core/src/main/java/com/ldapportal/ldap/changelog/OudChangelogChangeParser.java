// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.enums.LdapChangeOp;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldif.LDIFChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFModifyChangeRecord;
import com.unboundid.ldif.LDIFReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parses an OUD / DSEE {@code changeLogEntry} into a {@link ChangelogChange}
 * whose {@code rawPayload} matches the {@code ReplicationEnqueuer} shape
 * (pre-mapping). A {@code @Component}-free static helper, mirroring the
 * {@code DnMapper} / {@code AttributeMapper} style.
 *
 * <p>The {@code changes} attribute is an LDIF fragment (RFC 2849). Rather than
 * hand-roll a parser we reuse UnboundID's LDIF machinery, which already handles
 * base64 values, folded continuation lines, and the {@code -} modification
 * separators:
 * <ul>
 *   <li><b>modify</b> — wrap as {@code changetype: modify} + the blob and decode
 *       to {@code Modification[]};</li>
 *   <li><b>add</b> — decode the blob as an entry's attribute lines;</li>
 *   <li><b>delete</b> — no change content;</li>
 *   <li><b>modrdn</b> — read {@code newRDN} / {@code deleteOldRDN} /
 *       {@code newSuperior} directly.</li>
 * </ul>
 *
 * <p>A <b>placeholder DN</b> is used when feeding the blob to the LDIF decoder
 * because only the modifications / attributes are read back — never the parsed
 * DN (the real {@code sourceDn} comes from the {@code targetDN} attribute). This
 * sidesteps every DN-escaping edge case in the LDIF {@code dn:} line.
 *
 * <p>See {@code docs/plans/2026-06-03-changelog-replication-design.md} §5.
 */
public final class OudChangelogChangeParser {

    private OudChangelogChangeParser() {
    }

    /** DN fed to the LDIF decoder; never read back (see class javadoc). */
    private static final String PLACEHOLDER_DN = "cn=changelog-parse-placeholder";

    /**
     * Reconstruct an OUD {@code changeLogEntry} into a {@link ChangelogChange},
     * or empty if the entry carries no replicable change (missing
     * {@code targetDN}, or an unrecognised {@code changeType}).
     *
     * @throws ChangelogParseException if the {@code changes} blob is malformed.
     */
    public static Optional<ChangelogChange> parse(SearchResultEntry entry) {
        String changeType = entry.getAttributeValue("changeType");
        String targetDn = entry.getAttributeValue("targetDN");
        if (changeType == null || targetDn == null || targetDn.isBlank()) {
            return Optional.empty();
        }

        LdapChangeOp op = switch (changeType.toLowerCase(Locale.ROOT)) {
            case "add" -> LdapChangeOp.ADD;
            case "modify" -> LdapChangeOp.MODIFY;
            case "delete" -> LdapChangeOp.DELETE;
            case "modrdn", "moddn" -> LdapChangeOp.MODIFY_DN;
            default -> null;
        };
        if (op == null) {
            return Optional.empty();
        }

        String changes = entry.getAttributeValue("changes");
        Map<String, Object> payload = switch (op) {
            case ADD -> parseAdd(changes);
            case MODIFY -> parseModify(changes);
            case DELETE -> new LinkedHashMap<>();
            case MODIFY_DN -> parseModifyDn(entry);
        };
        return Optional.of(new ChangelogChange(op, targetDn, payload));
    }

    // ── per-operation parsing ─────────────────────────────────────────────────

    private static Map<String, Object> parseAdd(String changes) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        List<String> body = splitLdifLines(changes);
        if (!body.isEmpty()) {
            List<String> lines = new ArrayList<>(body.size() + 1);
            lines.add("dn: " + PLACEHOLDER_DN);
            lines.addAll(body);
            try {
                for (Attribute a : LDIFReader.decodeEntry(lines.toArray(new String[0])).getAttributes()) {
                    attributes.put(a.getName(), Arrays.asList(a.getValues()));
                }
            } catch (LDIFException e) {
                throw new ChangelogParseException(
                        "Malformed 'changes' LDIF on add changeLogEntry: " + e.getMessage(), e);
            }
        }
        // A real add always carries at least objectClass; an attribute-less add
        // would produce an invalid target write. Treat it as a poison entry so
        // the poller dead-letters it rather than emitting a broken operation.
        if (attributes.isEmpty()) {
            throw new ChangelogParseException(
                    "Add changeLogEntry carries no attributes in 'changes'", null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attributes", attributes);
        return payload;
    }

    private static Map<String, Object> parseModify(String changes) {
        List<Map<String, Object>> modifications = new ArrayList<>();
        List<String> body = splitLdifLines(changes);
        if (!body.isEmpty()) {
            List<String> lines = new ArrayList<>(body.size() + 2);
            lines.add("dn: " + PLACEHOLDER_DN);
            lines.add("changetype: modify");
            lines.addAll(body);
            LDIFChangeRecord record;
            try {
                record = LDIFReader.decodeChangeRecord(lines.toArray(new String[0]));
            } catch (LDIFException e) {
                throw new ChangelogParseException(
                        "Malformed 'changes' LDIF on modify changeLogEntry: " + e.getMessage(), e);
            }
            if (!(record instanceof LDIFModifyChangeRecord modRecord)) {
                throw new ChangelogParseException(
                        "Expected a modify change record from 'changes' but got "
                                + record.getClass().getSimpleName(), null);
            }
            for (Modification m : modRecord.getModifications()) {
                Map<String, Object> mod = new LinkedHashMap<>();
                mod.put("type", m.getModificationType().getName().toUpperCase(Locale.ROOT));
                mod.put("name", m.getAttributeName());
                // Delete-the-whole-attribute carries no values; mirror the
                // enqueuer's null/empty → List.of() contract so JSONB stays clean.
                String[] values = m.getValues();
                mod.put("values", (values == null || values.length == 0)
                        ? List.of() : Arrays.asList(values));
                modifications.add(mod);
            }
        }
        // A real modify records at least one modification; an empty one would
        // be a no-op the server rejects. Dead-letter rather than emit it.
        if (modifications.isEmpty()) {
            throw new ChangelogParseException(
                    "Modify changeLogEntry carries no modifications in 'changes'", null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modifications", modifications);
        return payload;
    }

    private static Map<String, Object> parseModifyDn(SearchResultEntry entry) {
        String newRdn = entry.getAttributeValue("newRDN");
        // newRDN is mandatory for a rename/move; without it the operation is
        // meaningless (a rename-to-null). Dead-letter rather than emit it.
        if (newRdn == null || newRdn.isBlank()) {
            throw new ChangelogParseException(
                    "Modrdn changeLogEntry is missing newRDN", null);
        }
        String newSuperior = entry.getAttributeValue("newSuperior");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("newRdn", newRdn);
        payload.put("deleteOldRdn", parseBoolean(entry.getAttributeValue("deleteOldRDN")));
        payload.put("newSuperiorDn",
                (newSuperior == null || newSuperior.isBlank()) ? null : newSuperior);
        return payload;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Split an LDIF blob into lines for the UnboundID decoder. Preserves
     * leading spaces (LDIF line folding) and normalises CRLF, but drops every
     * zero-length line — leading, interior, or trailing. We always feed a
     * <b>single</b> LDIF record to the decoder, so a blank line is never a
     * meaningful record separator here; left in place, the decoder treats one
     * as a record terminator and throws. Dropping them makes parsing resilient
     * to stray newlines without changing the decoded result. (A whitespace-only
     * line is a folding continuation, not blank, so it is kept.)
     */
    private static List<String> splitLdifLines(String blob) {
        if (blob == null || blob.isBlank()) {
            return List.of();
        }
        String[] raw = blob.split("\n", -1);
        List<String> lines = new ArrayList<>(raw.length);
        for (String r : raw) {
            String line = r.endsWith("\r") ? r.substring(0, r.length() - 1) : r;
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Parse an LDAP changelog boolean. OUD writes {@code TRUE}/{@code FALSE};
     * the draft-good-ldap-changelog format uses {@code 1}/{@code 0}. Trim first
     * so a stray surrounding space doesn't silently read as false.
     */
    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return "TRUE".equalsIgnoreCase(v) || "1".equals(v);
    }
}
