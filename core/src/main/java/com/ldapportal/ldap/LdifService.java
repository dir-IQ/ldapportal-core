// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.LdifImportResult.LdifImportError;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.LdapAdminException;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldif.LDIFAddChangeRecord;
import com.unboundid.ldif.LDIFChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exports LDAP entries in LDIF format (RFC 2849).
 *
 * <p>Supports single-entry and subtree exports. Binary attribute values are
 * base64-encoded. Results are streamed to an {@link OutputStream} so that
 * large subtrees do not accumulate in memory.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@LdapWriteAuthorized("LDIF import: applies change records and content entries directly.")
public class LdifService {

    private final LdapConnectionFactory connectionFactory;

    /**
     * User entries in the import are routed through {@link LdapUserService}
     * (the provisioning-plan SPI) rather than written raw, so vendor-aware
     * interceptors — e.g. ISVA {@code secUser} provisioning — fire for them.
     * Non-user entries and modify/delete/moddn change records stay on the
     * raw path. See {@link #applyParsed}.
     */
    private final LdapUserService userService;

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * One parsed LDIF record, or a parse error captured at {@code rowNumber}
     * (1-based). Shared by the apply path and the preview so both classify and
     * execute the <em>exact same</em> records — no parser drift.
     */
    public record ParsedRecord(int rowNumber, LDIFRecord record, String parseError) {
        public boolean isError() { return parseError != null; }
    }

    /**
     * Parse an LDIF stream into ordered records, capturing per-record parse
     * errors (RFC 2849) inline rather than aborting. A fatal, non-recoverable
     * parse error stops the stream (mirroring the previous loop's
     * {@code mayContinueReading()} behaviour).
     */
    public List<ParsedRecord> parse(InputStream ldifContent) {
        List<ParsedRecord> records = new ArrayList<>();
        int row = 0;
        try (LDIFReader reader = new LDIFReader(ldifContent)) {
            while (true) {
                LDIFRecord record;
                try {
                    record = reader.readLDIFRecord();
                } catch (LDIFException e) {
                    row++;
                    records.add(new ParsedRecord(row, null,
                            "Parse error at line " + e.getDataLines() + ": " + e.getMessage()));
                    if (!e.mayContinueReading()) break;
                    continue;
                }
                if (record == null) break; // end of stream
                row++;
                records.add(new ParsedRecord(row, record, null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    /**
     * Imports LDIF content into the directory.
     *
     * <p>Supports both content records (plain entries → add) and change records
     * (add/modify/delete/moddn).  Each record is processed individually; a
     * single failure does not abort the remaining records.</p>
     *
     * @param dc              directory connection
     * @param ldifContent     raw LDIF byte stream
     * @param conflict        how to handle entries that already exist
     * @param dryRun          if true, parse/validate only — do not apply changes.
     *                        Prefer the preview flow ({@code LdifPreviewService});
     *                        this shallow dry-run is retained for compatibility.
     * @return aggregate result with per-entry error details
     */
    public LdifImportResult importLdif(DirectoryConnection dc,
                                       InputStream ldifContent,
                                       ConflictHandling conflict,
                                       boolean dryRun) {
        return importLdif(dc, ldifContent, conflict, dryRun, false);
    }

    /**
     * Import overload carrying the vendor-overlay suppression flag (see
     * {@link #applyParsedRecords}). Used by the direct (non-preview) import
     * endpoint when the operator declined vendor provisioning.
     */
    public LdifImportResult importLdif(DirectoryConnection dc,
                                       InputStream ldifContent,
                                       ConflictHandling conflict,
                                       boolean dryRun,
                                       boolean suppressVendorOverlay) {
        List<ParsedRecord> records = parse(ldifContent);
        if (dryRun) {
            int skipped = 0, failed = 0;
            List<LdifImportError> errors = new ArrayList<>();
            for (ParsedRecord pr : records) {
                if (pr.isError()) {
                    failed++;
                    errors.add(new LdifImportError(null, pr.parseError()));
                } else {
                    skipped++;
                }
            }
            log.info("LDIF dry-run complete: parsed={}, parseErrors={}", skipped, failed);
            return new LdifImportResult(0, 0, skipped, failed, errors);
        }
        return applyParsedRecords(dc, records, conflict, suppressVendorOverlay);
    }

    /**
     * Execute pre-parsed records against the directory. Used by the preview's
     * apply step so the operator applies the exact records they previewed
     * (no re-upload, no re-parse).
     *
     * @param suppressVendorOverlay when true, user adds are routed through the
     *        provisioning SPI with the vendor overlay suppressed — the operator
     *        declined e.g. ISVA {@code secUser} provisioning for this import.
     */
    public LdifImportResult applyParsedRecords(DirectoryConnection dc,
                                               List<ParsedRecord> records,
                                               ConflictHandling conflict,
                                               boolean suppressVendorOverlay) {
        return connectionFactory.withConnection(dc,
                conn -> applyParsed(records, conn, conflict, dc, suppressVendorOverlay));
    }

    /** Outcome of applying one add record, for the loop's counters. */
    private enum AddOutcome { ADDED, UPDATED, SKIPPED }

    private LdifImportResult applyParsed(List<ParsedRecord> records,
                                         FullLDAPInterface conn,
                                         ConflictHandling conflict,
                                         DirectoryConnection dc,
                                         boolean suppressVendorOverlay) {
        Set<String> userOcs = DirectoryObjectClassDefaults.effectiveUserObjectClassSet(dc);

        // Safety: if the import itself already contains secUser entries it is a
        // self-describing export (inline overlays, or paired linked-mode
        // secUser entries). Auto-provisioning a fresh overlay on top would
        // duplicate it — and in linked mode the secUser ADD would hit
        // ENTRY_ALREADY_EXISTS and trigger the compensation DELETE of the
        // just-added demographic. So fall back to raw writes for the whole
        // file in that case, exactly as if the operator had opted out.
        boolean fileHasSecUser = records.stream()
                .map(LdifService::entryOf)
                .anyMatch(e -> e != null && hasSecUserObjectClass(e));
        boolean provisionOverlay = !suppressVendorOverlay && !fileHasSecUser;
        if (fileHasSecUser && !suppressVendorOverlay) {
            log.info("LDIF import on directory '{}' contains secUser entries — treating as a "
                    + "self-describing export and writing entries as-is (no extra overlay provisioning).",
                    dc.getDisplayName());
        }

        int added = 0, updated = 0, skipped = 0, failed = 0;
        List<LdifImportError> errors = new ArrayList<>();

        for (ParsedRecord pr : records) {
            if (pr.isError()) {
                failed++;
                errors.add(new LdifImportError(null, pr.parseError()));
                continue;
            }
            LDIFRecord record = pr.record();
            String dn = record.getDN();
            try {
                AddOutcome outcome;
                if (record instanceof LDIFAddChangeRecord addRec) {
                    outcome = applyAdd(dc, conn, addRec.getEntryToAdd(), conflict, userOcs, provisionOverlay);
                } else if (record instanceof LDIFChangeRecord changeRecord) {
                    // modify / delete / moddn — applied raw; lumped as "updated".
                    changeRecord.processChange(conn);
                    outcome = AddOutcome.UPDATED;
                } else if (record instanceof Entry entry) {
                    outcome = applyAdd(dc, conn, entry, conflict, userOcs, provisionOverlay);
                } else {
                    continue; // unknown record shape — nothing to apply
                }
                switch (outcome) {
                    case ADDED -> added++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            } catch (LDAPException ex) {
                failed++;
                errors.add(new LdifImportError(dn, ex.getMessage()));
                log.warn("LDIF import failed for dn='{}': {}", dn, ex.getMessage());
            } catch (LdapAdminException ex) {
                // Surfaced by the provisioning SPI path (createUser) for a
                // non-conflict failure — already wrapped with context.
                failed++;
                errors.add(new LdifImportError(dn, ex.getMessage()));
                log.warn("LDIF import failed for dn='{}': {}", dn, ex.getMessage());
            }
        }

        log.info("LDIF import complete: added={}, updated={}, skipped={}, failed={}",
                added, updated, skipped, failed);
        return new LdifImportResult(added, updated, skipped, failed, errors);
    }

    /**
     * Apply one entry-add. User entries (per the directory's object-class
     * configuration) are routed through {@link LdapUserService#createUser}
     * so vendor interceptors fire; everything else is written raw. Both
     * paths share the conflict handling.
     */
    private AddOutcome applyAdd(DirectoryConnection dc,
                                FullLDAPInterface conn,
                                Entry entry,
                                ConflictHandling conflict,
                                Set<String> userOcs,
                                boolean provisionOverlay) throws LDAPException {
        String dn = entry.getDN();

        // Candidate for vendor-overlay provisioning: a user entry that does
        // not already carry the overlay itself. Self-describing secUser
        // entries (overlay already present) are written as-is.
        boolean candidate = provisionOverlay
                && isUserEntry(entry, userOcs)
                && !hasSecUserObjectClass(entry);

        if (candidate) {
            try {
                userService.createUser(dc, dn, toAttributeMap(entry), null, false);
                return AddOutcome.ADDED;
            } catch (LdapAdminException ex) {
                if (!isAlreadyExists(ex)) {
                    throw ex;
                }
                return applyConflict(conn, entry, conflict);
            }
        }

        try {
            conn.add(entry);
            return AddOutcome.ADDED;
        } catch (LDAPException ex) {
            if (ex.getResultCode() != ResultCode.ENTRY_ALREADY_EXISTS) {
                throw ex;
            }
            return applyConflict(conn, entry, conflict);
        }
    }

    /** Conflict resolution shared by the SPI and raw add paths. */
    private AddOutcome applyConflict(FullLDAPInterface conn, Entry entry, ConflictHandling conflict)
            throws LDAPException {
        if (conflict == ConflictHandling.OVERWRITE) {
            // Replace all attributes from the LDIF entry (objectClass left
            // intact — it's structural and a REPLACE could strip auxiliary
            // classes the live entry needs).
            List<Modification> mods = new ArrayList<>();
            for (Attribute attr : entry.getAttributes()) {
                if (attr.getBaseName().equalsIgnoreCase("objectClass")) continue;
                mods.add(new Modification(ModificationType.REPLACE,
                        attr.getBaseName(), attr.getValues()));
            }
            if (!mods.isEmpty()) {
                conn.modify(entry.getDN(), mods);
            }
            return AddOutcome.UPDATED;
        }
        // SKIP / PROMPT — leave the existing entry untouched.
        return AddOutcome.SKIPPED;
    }

    /** The entry carried by a content record or an add change record; null otherwise. */
    private static Entry entryOf(ParsedRecord pr) {
        if (pr.isError()) return null;
        LDIFRecord record = pr.record();
        if (record instanceof LDIFAddChangeRecord addRec) return addRec.getEntryToAdd();
        if (record instanceof Entry entry) return entry;
        return null;
    }

    private static boolean isUserEntry(Entry entry, Set<String> userOcs) {
        String[] ocs = entry.getObjectClassValues();
        if (ocs == null) return false;
        for (String oc : ocs) {
            if (userOcs.contains(oc.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean hasSecUserObjectClass(Entry entry) {
        String[] ocs = entry.getObjectClassValues();
        if (ocs == null) return false;
        for (String oc : ocs) {
            if ("secuser".equals(oc.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Map<String, List<String>> toAttributeMap(Entry entry) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Attribute attr : entry.getAttributes()) {
            map.put(attr.getName(), Arrays.asList(attr.getValues()));
        }
        return map;
    }

    /**
     * Whether a wrapped provisioning failure was an
     * {@code ENTRY_ALREADY_EXISTS}. The SPI wraps the original
     * {@link LDAPException} as the cause, so we walk the cause chain and
     * test its result code — robust regardless of how the message reads.
     * Falls back to message matching if the chain was flattened.
     */
    private static boolean isAlreadyExists(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof LDAPException le
                    && le.getResultCode() == ResultCode.ENTRY_ALREADY_EXISTS) {
                return true;
            }
        }
        String msg = ex.getMessage();
        return msg != null
                && (msg.contains(ResultCode.ENTRY_ALREADY_EXISTS.getName())
                    || msg.contains("(" + ResultCode.ENTRY_ALREADY_EXISTS.intValue() + ")"));
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Exports a single entry at {@code dn} as LDIF.
     */
    public void exportEntry(DirectoryConnection dc, String dn, OutputStream out) {
        connectionFactory.withConnection(dc, conn -> {
            try {
                SearchResultEntry entry = conn.getEntry(dn);
                if (entry != null) {
                    Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    writeEntry(writer, entry);
                    writer.flush();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    /**
     * Exports entries matching the given scope from {@code baseDn} as LDIF.
     *
     * @param dc     directory connection
     * @param baseDn search base DN
     * @param scope  LDAP search scope (BASE, ONE, SUB)
     * @param out    output stream to write LDIF content to
     */
    public void exportSubtree(DirectoryConnection dc, String baseDn,
                              SearchScope scope, OutputStream out) {
        connectionFactory.withConnection(dc, conn -> {
            try {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                int pageSize = dc.getPagingSize();
                ASN1OctetString cookie = null;
                boolean firstEntry = true;

                do {
                    SearchRequest request = new SearchRequest(
                            baseDn, scope,
                            Filter.createPresenceFilter("objectClass"),
                            "*", "+"); // all user + operational attributes
                    request.addControl(new SimplePagedResultsControl(pageSize, cookie));

                    SearchResult result;
                    try {
                        result = conn.search(request);
                    } catch (LDAPSearchException e) {
                        if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                            log.debug("Base '{}' does not exist — empty export", baseDn);
                            return null;
                        }
                        throw e;
                    }

                    for (SearchResultEntry entry : result.getSearchEntries()) {
                        if (!firstEntry) {
                            writer.write("\n");
                        }
                        writeEntry(writer, entry);
                        firstEntry = false;
                    }

                    SimplePagedResultsControl pageResponse =
                            SimplePagedResultsControl.get(result);
                    cookie = (pageResponse != null && pageResponse.moreResultsToReturn())
                            ? pageResponse.getCookie()
                            : null;
                } while (cookie != null && cookie.getValue().length > 0);

                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    /**
     * Writes a single LDAP entry in LDIF format.
     */
    private void writeEntry(Writer writer, SearchResultEntry entry) throws IOException {
        writer.write("dn: ");
        writeLdifValue(writer, entry.getDN());
        writer.write("\n");

        for (Attribute attr : entry.getAttributes()) {
            for (byte[] rawValue : attr.getValueByteArrays()) {
                if (isSafeString(rawValue)) {
                    writer.write(attr.getBaseName());
                    writer.write(": ");
                    writer.write(new String(rawValue, StandardCharsets.UTF_8));
                    writer.write("\n");
                } else {
                    // Base64-encode binary values
                    writer.write(attr.getBaseName());
                    writer.write(":: ");
                    writer.write(Base64.getEncoder().encodeToString(rawValue));
                    writer.write("\n");
                }
            }
        }
    }

    /**
     * Writes a DN or string value, base64-encoding if it contains unsafe characters.
     */
    private void writeLdifValue(Writer writer, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (isSafeString(bytes)) {
            writer.write(value);
        } else {
            writer.write(": ");
            writer.write(Base64.getEncoder().encodeToString(bytes));
        }
    }

    /**
     * Checks whether a byte array is a safe LDIF string (printable ASCII,
     * doesn't start with space/colon/less-than, no NUL bytes).
     */
    private boolean isSafeString(byte[] value) {
        if (value.length == 0) return true;
        byte first = value[0];
        if (first == ' ' || first == ':' || first == '<' || first == '\n' || first == '\r') {
            return false;
        }
        for (byte b : value) {
            if (b == 0 || (b & 0xFF) > 127) {
                return false;
            }
        }
        return true;
    }
}
