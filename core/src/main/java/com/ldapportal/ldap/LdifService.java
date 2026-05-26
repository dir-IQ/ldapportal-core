// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.LdifImportResult.LdifImportError;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
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
import java.util.Base64;
import java.util.List;

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
public class LdifService {

    private final LdapConnectionFactory connectionFactory;

    // ── Import ────────────────────────────────────────────────────────────────

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
     * @param dryRun          if true, validate only — do not apply changes
     * @return aggregate result with per-entry error details
     */
    public LdifImportResult importLdif(DirectoryConnection dc,
                                       InputStream ldifContent,
                                       ConflictHandling conflict,
                                       boolean dryRun) {
        return connectionFactory.withConnection(dc, conn -> {
            int added = 0, updated = 0, skipped = 0, failed = 0;
            List<LdifImportError> errors = new ArrayList<>();

            try (LDIFReader reader = new LDIFReader(ldifContent)) {
                LDIFRecord record;
                while (true) {
                    try {
                        record = reader.readLDIFRecord();
                    } catch (LDIFException e) {
                        failed++;
                        errors.add(new LdifImportError(null,
                                "Parse error at line " + e.getDataLines() + ": " + e.getMessage()));
                        if (!e.mayContinueReading()) break;
                        continue;
                    }
                    if (record == null) break; // end of stream

                    String dn = record.getDN();
                    try {
                        if (record instanceof LDIFChangeRecord changeRecord) {
                            if (dryRun) { skipped++; continue; }
                            changeRecord.processChange(conn);
                            // Count change records as "added" for simplicity;
                            // delete/modify are lumped into "updated"
                            if (changeRecord instanceof com.unboundid.ldif.LDIFAddChangeRecord) {
                                added++;
                            } else {
                                updated++;
                            }
                        } else if (record instanceof Entry entry) {
                            if (dryRun) { skipped++; continue; }
                            try {
                                conn.add(entry);
                                added++;
                            } catch (LDAPException ex) {
                                if (ex.getResultCode() == ResultCode.ENTRY_ALREADY_EXISTS) {
                                    switch (conflict) {
                                        case OVERWRITE -> {
                                            // Replace all attributes from the LDIF entry
                                            List<Modification> mods = new ArrayList<>();
                                            for (Attribute attr : entry.getAttributes()) {
                                                if (attr.getBaseName().equalsIgnoreCase("objectClass")) continue;
                                                mods.add(new Modification(
                                                        ModificationType.REPLACE,
                                                        attr.getBaseName(),
                                                        attr.getValues()));
                                            }
                                            if (!mods.isEmpty()) {
                                                conn.modify(dn, mods);
                                            }
                                            updated++;
                                        }
                                        case SKIP, PROMPT -> {
                                            skipped++;
                                        }
                                    }
                                } else {
                                    throw ex;
                                }
                            }
                        }
                    } catch (LDAPException ex) {
                        failed++;
                        errors.add(new LdifImportError(dn, ex.getMessage()));
                        log.warn("LDIF import failed for dn='{}': {}", dn, ex.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            log.info("LDIF import complete: added={}, updated={}, skipped={}, failed={}",
                    added, updated, skipped, failed);
            return new LdifImportResult(added, updated, skipped, failed, errors);
        });
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
