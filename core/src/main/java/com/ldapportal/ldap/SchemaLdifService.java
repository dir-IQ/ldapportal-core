// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.schema.ApplySchemaPreviewRequest;
import com.ldapportal.dto.schema.SchemaElementAction;
import com.ldapportal.dto.schema.SchemaElementKind;
import com.ldapportal.dto.schema.SchemaPreviewElement;
import com.ldapportal.dto.schema.SchemaPreviewIssue;
import com.ldapportal.dto.schema.SchemaPreviewSummary;
import com.ldapportal.dto.schema.SchemaUpdateResult;
import com.ldapportal.dto.schema.SchemaUpdateResult.SchemaUpdateError;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdifService.ParsedRecord;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.ldapportal.ldap.schema.SchemaWriteStrategy;
import com.ldapportal.ldap.schema.SchemaWriteStrategyResolver;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFAddChangeRecord;
import com.unboundid.ldif.LDIFChangeRecord;
import com.unboundid.ldif.LDIFDeleteChangeRecord;
import com.unboundid.ldif.LDIFModifyChangeRecord;
import com.unboundid.ldif.LDIFModifyDNChangeRecord;
import com.unboundid.ldif.LDIFRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Superadmin schema updates via LDIF: parse an uploaded LDIF, classify each
 * attributeType / objectClass against the live directory schema (add-new vs
 * modify-existing vs unsupported), cache the result under a short-lived
 * {@code previewId}, and — on a separate confirmed call — apply the exact
 * records previewed.
 *
 * <p>Schema writes are vendor-specific and live <em>outside</em> the data DIT,
 * so this path is deliberately separate from the entry-level LDIF import and
 * its DN-scope guard. The security boundary here is the per-vendor
 * {@link SchemaWriteStrategy#isSchemaTargetDn}: a record targeting anything but
 * the schema container is rejected. OpenLDAP writes go through a caller-supplied
 * {@code cn=config} bind; OpenDJ writes use the directory's normal bind. Neither
 * participates in sync change-capture ({@code withConnectionUnreplicated}).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@LdapWriteAuthorized("Applies superadmin schema-LDIF changes (attributeTypes / "
        + "objectClasses) to the schema container after a preview; scope-guarded to "
        + "the schema subentry, unreplicated, audited by SchemaManagementController.")
public class SchemaLdifService {

    private final LdapConnectionFactory connectionFactory;
    private final LdapSchemaService schemaService;
    private final LdifService ldifService;
    private final SchemaWriteStrategyResolver strategyResolver;

    @Value("${ldapportal.schema.preview.ttl-minutes:30}")
    private long ttlMinutes;
    @Value("${ldapportal.schema.preview.max-entries:20}")
    private int maxCacheEntries;

    private final Map<UUID, CachedSchemaPreview> cache = new ConcurrentHashMap<>();

    private record CachedSchemaPreview(UUID ownerId,
                                       UUID directoryId,
                                       DirectoryType vendor,
                                       List<ParsedRecord> records,
                                       boolean blocking,
                                       Instant createdAt) {}

    /** One schema-element definition extracted from an LDIF record. */
    private record RawDef(SchemaElementKind kind, String definition, ModificationType modType) {}

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Parse + classify the upload against the live schema and cache the result.
     *
     * @throws LdapOperationException if the directory type has no schema-write strategy (→ 422)
     */
    public SchemaPreviewSummary createPreview(DirectoryConnection dc, InputStream upload, UUID ownerId) {
        SchemaWriteStrategy strat = strategyResolver.resolve(dc.getDirectoryType());
        List<ParsedRecord> records = ldifService.parse(upload);
        Schema live = schemaService.fetchSchema(dc);

        List<SchemaPreviewElement> elements = new ArrayList<>();
        for (ParsedRecord pr : records) {
            elements.addAll(classifyRecord(pr, strat, live));
        }

        int addNew = 0, modifyExisting = 0, unsupported = 0, errors = 0;
        boolean blocking = false;
        for (SchemaPreviewElement el : elements) {
            switch (el.action()) {
                case ADD_NEW -> addNew++;
                case MODIFY_EXISTING -> modifyExisting++;
                case UNSUPPORTED -> unsupported++;
            }
            if (el.issues().stream().anyMatch(i -> SchemaPreviewIssue.ERROR.equals(i.severity()))) {
                errors++;
            }
            if (el.blocking()) {
                blocking = true;
            }
        }

        UUID previewId = UUID.randomUUID();
        store(previewId, new CachedSchemaPreview(ownerId, dc.getId(), dc.getDirectoryType(),
                records, blocking, Instant.now()));

        log.info("Schema preview {} for dir {} ({}): {} elements (addNew={} modifyExisting={} "
                        + "unsupported={} errors={}, blocking={})",
                previewId, dc.getId(), dc.getDirectoryType(), elements.size(),
                addNew, modifyExisting, unsupported, errors, blocking);

        return new SchemaPreviewSummary(previewId.toString(), dc.getId(), dc.getDirectoryType(),
                elements.size(),
                new SchemaPreviewSummary.Counts(addNew, modifyExisting, unsupported, errors),
                elements, blocking);
    }

    private List<SchemaPreviewElement> classifyRecord(ParsedRecord pr, SchemaWriteStrategy strat, Schema live) {
        int row = pr.rowNumber();
        if (pr.isError()) {
            return List.of(new SchemaPreviewElement(row, null, null, null,
                    SchemaElementAction.UNSUPPORTED, null, null,
                    List.of(SchemaPreviewIssue.parseError(pr.parseError()))));
        }

        LDIFRecord rec = pr.record();
        String dn = rec.getDN();

        // Deleting or renaming a schema entry is never supported through this tool.
        if (rec instanceof LDIFDeleteChangeRecord || rec instanceof LDIFModifyDNChangeRecord) {
            return List.of(new SchemaPreviewElement(row, null, null, null,
                    SchemaElementAction.UNSUPPORTED, dn, null,
                    List.of(SchemaPreviewIssue.deleteUnsupported())));
        }

        boolean scopeOk = strat.isSchemaTargetDn(dn);
        List<RawDef> defs = extractDefs(rec, strat);
        if (defs.isEmpty()) {
            // No schema-element values in this record. If it targets the schema
            // container it's a harmless no-op (e.g. only cn/objectClass on a new
            // olcSchemaConfig entry); otherwise flag the out-of-scope DN.
            if (!scopeOk) {
                return List.of(new SchemaPreviewElement(row, null, null, null,
                        SchemaElementAction.UNSUPPORTED, dn, null,
                        List.of(SchemaPreviewIssue.outOfScope(strat.schemaContainerDescription()))));
            }
            return List.of();
        }

        List<SchemaPreviewElement> out = new ArrayList<>(defs.size());
        for (RawDef d : defs) {
            out.add(classifyDef(row, dn, d, scopeOk, strat, live));
        }
        return out;
    }

    private SchemaPreviewElement classifyDef(int row, String dn, RawDef d, boolean scopeOk,
                                             SchemaWriteStrategy strat, Schema live) {
        List<SchemaPreviewIssue> issues = new ArrayList<>();
        String normalized = strat.normalizeDefinition(d.definition());

        String name;
        String oid;
        boolean existsByName;
        String oidOwnerName;
        try {
            if (d.kind() == SchemaElementKind.ATTRIBUTE_TYPE) {
                AttributeTypeDefinition def = new AttributeTypeDefinition(normalized);
                name = def.getNameOrOID();
                oid = def.getOID();
                existsByName = attributeExistsByName(live, def);
                AttributeTypeDefinition byOid = oid == null ? null : live.getAttributeType(oid);
                oidOwnerName = byOid == null ? null : byOid.getNameOrOID();
            } else {
                ObjectClassDefinition def = new ObjectClassDefinition(normalized);
                name = def.getNameOrOID();
                oid = def.getOID();
                existsByName = objectClassExistsByName(live, def);
                ObjectClassDefinition byOid = oid == null ? null : live.getObjectClass(oid);
                oidOwnerName = byOid == null ? null : byOid.getNameOrOID();
            }
        } catch (LDAPException e) {
            issues.add(SchemaPreviewIssue.parseError(e.getMessage()));
            return new SchemaPreviewElement(row, d.kind(), null, null,
                    SchemaElementAction.UNSUPPORTED, dn, normalized, issues);
        }

        SchemaElementAction action;
        if (!scopeOk) {
            issues.add(SchemaPreviewIssue.outOfScope(strat.schemaContainerDescription()));
            action = SchemaElementAction.UNSUPPORTED;
        } else if (d.modType() == ModificationType.DELETE) {
            if (strat.supportsModifyExisting()) {
                action = SchemaElementAction.MODIFY_EXISTING;
            } else {
                issues.add(SchemaPreviewIssue.modifyUnsupported(strat.schemaContainerDescription()));
                action = SchemaElementAction.UNSUPPORTED;
            }
        } else if (existsByName) {
            if (strat.supportsModifyExisting()) {
                issues.add(SchemaPreviewIssue.modifiesExisting());
                action = SchemaElementAction.MODIFY_EXISTING;
            } else {
                issues.add(SchemaPreviewIssue.modifyUnsupported(strat.schemaContainerDescription()));
                action = SchemaElementAction.UNSUPPORTED;
            }
        } else if (oidOwnerName != null) {
            issues.add(SchemaPreviewIssue.oidCollision(oid, oidOwnerName));
            action = SchemaElementAction.UNSUPPORTED;
        } else {
            action = SchemaElementAction.ADD_NEW;
        }

        return new SchemaPreviewElement(row, d.kind(), name, oid, action, dn, normalized, issues);
    }

    private List<RawDef> extractDefs(LDIFRecord rec, SchemaWriteStrategy strat) {
        List<RawDef> defs = new ArrayList<>();
        if (rec instanceof LDIFModifyChangeRecord mod) {
            for (Modification m : mod.getModifications()) {
                SchemaElementKind kind = kindOf(m.getAttributeName(), strat);
                if (kind == null) {
                    continue;
                }
                for (String v : m.getValues()) {
                    defs.add(new RawDef(kind, v, m.getModificationType()));
                }
            }
        } else if (rec instanceof LDIFAddChangeRecord add) {
            collectFromEntry(add.getEntryToAdd(), strat, defs);
        } else if (rec instanceof Entry entry) {
            collectFromEntry(entry, strat, defs);
        }
        return defs;
    }

    private void collectFromEntry(Entry entry, SchemaWriteStrategy strat, List<RawDef> defs) {
        for (Attribute a : entry.getAttributes()) {
            SchemaElementKind kind = kindOf(a.getName(), strat);
            if (kind == null) {
                continue;
            }
            for (String v : a.getValues()) {
                defs.add(new RawDef(kind, v, null));
            }
        }
    }

    private SchemaElementKind kindOf(String attrName, SchemaWriteStrategy strat) {
        String n = attrName.toLowerCase(Locale.ROOT);
        int semi = n.indexOf(';'); // strip attribute options
        if (semi >= 0) {
            n = n.substring(0, semi);
        }
        if (strat.attributeTypeValueAttrs().contains(n)) {
            return SchemaElementKind.ATTRIBUTE_TYPE;
        }
        if (strat.objectClassValueAttrs().contains(n)) {
            return SchemaElementKind.OBJECT_CLASS;
        }
        return null;
    }

    private static boolean attributeExistsByName(Schema live, AttributeTypeDefinition def) {
        for (String nm : def.getNames()) {
            if (live.getAttributeType(nm) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean objectClassExistsByName(Schema live, ObjectClassDefinition def) {
        for (String nm : def.getNames()) {
            if (live.getObjectClass(nm) != null) {
                return true;
            }
        }
        return false;
    }

    // ── Apply / export ─────────────────────────────────────────────────────────

    /**
     * Apply the previewed records. Refuses if the preview had any blocking
     * element. OpenLDAP requires config-admin credentials in {@code request};
     * OpenDJ ignores them.
     *
     * @throws IllegalArgumentException (→ 400) if the preview is blocking or OpenLDAP config creds are missing
     * @throws LdapOperationException (→ 422) on bind/write failure
     */
    public SchemaUpdateResult apply(UUID previewId, UUID ownerId, DirectoryConnection dc,
                                    ApplySchemaPreviewRequest request) {
        CachedSchemaPreview cp = require(previewId, ownerId);
        if (cp.blocking()) {
            throw new IllegalArgumentException(
                    "The schema preview has blocking issues; resolve them before applying.");
        }
        SchemaWriteStrategy strat = strategyResolver.resolve(cp.vendor());
        boolean addNewOnly = request != null && request.addNewOnly();

        List<LDIFRecord> selected;
        if (addNewOnly) {
            // Re-classify against the current live schema and keep only the
            // ADD_NEW definitions, dropping updates to existing elements. This is
            // per-definition, so a bundled cn=schema record applies only its new
            // values. Re-fetching (vs. reusing the preview) also skips anything
            // that became existing since the preview was taken.
            selected = filterToAddNew(cp.records(), strat, schemaService.fetchSchema(dc));
        } else {
            selected = applicableRecords(cp.records());
        }
        // Rewrite subschema entry-dumps into modifies of the existing container
        // (OpenDJ/OUD) so a changetype-less upload isn't sent as add(cn=schema).
        List<LDIFRecord> records = normalizeContainerWrites(selected, strat);

        SchemaUpdateResult result;
        if (records.isEmpty()) {
            // Nothing to write (e.g. add-only over an all-modify preview): return a
            // no-op result without opening a connection or demanding config creds.
            result = new SchemaUpdateResult(0, 0, List.of());
        } else if (strat.requiresConfigConnection()) {
            String bindDn = request == null ? null : request.configBindDn();
            String password = request == null ? null : request.configPassword();
            if (isBlank(bindDn) || isBlank(password)) {
                throw new IllegalArgumentException(
                        "This directory writes schema under cn=config; a config-admin bind DN "
                                + "and password are required to apply.");
            }
            result = applyViaConfigConnection(dc, records, strat, bindDn, password);
        } else {
            result = connectionFactory.withConnectionUnreplicated(dc, conn -> applyRecords(conn, records, strat));
        }

        cache.remove(previewId);
        log.info("Applied schema preview {} to dir {}: applied={} failed={} (addNewOnly={})",
                previewId, dc.getId(), result.applied(), result.failed(), addNewOnly);
        return result;
    }

    private SchemaUpdateResult applyViaConfigConnection(DirectoryConnection dc, List<LDIFRecord> records,
                                                        SchemaWriteStrategy strat, String bindDn, String password) {
        LDAPConnection conn = connectionFactory.openUnboundConnection(dc);
        try {
            conn.bind(new SimpleBindRequest(bindDn, password));
            return applyRecords(conn, records, strat);
        } catch (LDAPException e) {
            throw new LdapOperationException(
                    "Config-admin bind failed on [" + dc.getDisplayName() + "]: " + e.getMessage(), e);
        } finally {
            conn.close();
        }
    }

    private SchemaUpdateResult applyRecords(LDAPInterface conn, List<LDIFRecord> records, SchemaWriteStrategy strat) {
        int applied = 0;
        int failed = 0;
        List<SchemaUpdateError> errors = new ArrayList<>();
        for (LDIFRecord rec : records) {
            // One record can carry several element definitions — e.g. a single
            // "changetype: modify / add: attributeTypes" bundling many values. Count
            // by element so applied/failed match the preview's element count rather
            // than the number of LDAP operations (a bundled modify is one op).
            int elements = Math.max(1, extractDefs(rec, strat).size());
            try {
                if (rec instanceof LDIFChangeRecord change) {
                    change.processChange(conn);
                } else if (rec instanceof Entry entry) {
                    conn.add(entry);
                } else {
                    continue;
                }
                applied += elements;
            } catch (LDAPException ex) {
                failed += elements;
                errors.add(new SchemaUpdateError(rec.getDN(), ex.getMessage()));
            }
        }
        return new SchemaUpdateResult(applied, failed, errors);
    }

    /** The non-error records of a preview, in order, as raw LDIF records to replay. */
    private static List<LDIFRecord> applicableRecords(List<ParsedRecord> records) {
        List<LDIFRecord> out = new ArrayList<>();
        for (ParsedRecord pr : records) {
            if (!pr.isError()) {
                out.add(pr.record());
            }
        }
        return out;
    }

    /**
     * Vendor-normalize the records to actually send to the directory. For
     * directories whose schema is a single, always-present subentry (OpenDJ/OUD
     * {@code cn=schema}), an uploaded schema <em>entry</em> — a subschema dump
     * with no {@code changetype}, the shape {@code ldapsearch} and this app's own
     * export produce — is rewritten as {@code MODIFY} / {@code add} of its
     * {@code attributeTypes} / {@code objectClasses} values. Sending it verbatim
     * would be an {@code add} of {@code cn=schema}, which the server rejects with
     * "entry already exists". Each definition becomes its own modify so one
     * already-present (or malformed) element can't abort the whole import, and
     * the applied/failed tally stays element-granular.
     *
     * <p>Explicit change records, and entry-adds for vendors that create child
     * schema entries (OpenLDAP under {@code cn=schema,cn=config}), pass through
     * unchanged.</p>
     */
    private List<LDIFRecord> normalizeContainerWrites(List<LDIFRecord> records, SchemaWriteStrategy strat) {
        List<LDIFRecord> out = new ArrayList<>();
        for (LDIFRecord rec : records) {
            out.addAll(toWriteRecords(rec, strat));
        }
        return out;
    }

    /** Package-private for direct unit testing of the entry→modify rewrite. */
    List<LDIFRecord> toWriteRecords(LDIFRecord rec, SchemaWriteStrategy strat) {
        boolean contentOrAdd = rec instanceof Entry || rec instanceof LDIFAddChangeRecord;
        if (!contentOrAdd || !strat.writesToExistingContainer() || !strat.isSchemaTargetDn(rec.getDN())) {
            return List.of(rec);
        }
        Entry entry = rec instanceof LDIFAddChangeRecord add ? add.getEntryToAdd() : (Entry) rec;
        List<LDIFRecord> out = new ArrayList<>();
        for (Attribute a : entry.getAttributes()) {
            if (kindOf(a.getName(), strat) == null) {
                continue; // objectClass / cn / nsSchemaCSN / aci … are not element definitions
            }
            for (String v : a.getValues()) {
                out.add(new LDIFModifyChangeRecord(entry.getDN(),
                        new Modification(ModificationType.ADD, a.getName(), v)));
            }
        }
        return out;
    }

    // ── Add-only filtering ──────────────────────────────────────────────────────

    /**
     * Rebuild the preview's records keeping only the {@code ADD_NEW} definitions
     * (new attributeTypes / objectClasses), dropping updates to existing elements.
     * Filtering is per-definition: a modify record keeps only its ADD modifications
     * whose values are new, and an add/entry record keeps its structural attributes
     * plus only its new schema values. Records left with nothing to add are omitted.
     *
     * <p>Package-private for direct unit testing of the element-level filter.</p>
     */
    List<LDIFRecord> filterToAddNew(List<ParsedRecord> records, SchemaWriteStrategy strat, Schema live) {
        List<LDIFRecord> out = new ArrayList<>();
        for (ParsedRecord pr : records) {
            if (pr.isError()) {
                continue;
            }
            LDIFRecord kept = keepOnlyAddNew(pr.record(), strat, live);
            if (kept != null) {
                out.add(kept);
            }
        }
        return out;
    }

    private LDIFRecord keepOnlyAddNew(LDIFRecord rec, SchemaWriteStrategy strat, Schema live) {
        String dn = rec.getDN();
        // Out-of-scope, delete, and modifyDN records are never "adds".
        if (!strat.isSchemaTargetDn(dn)
                || rec instanceof LDIFDeleteChangeRecord
                || rec instanceof LDIFModifyDNChangeRecord) {
            return null;
        }

        if (rec instanceof LDIFModifyChangeRecord mod) {
            List<Modification> kept = new ArrayList<>();
            for (Modification m : mod.getModifications()) {
                // A delete/replace of a schema value is a modify-existing, not an add.
                if (m.getModificationType() != ModificationType.ADD) {
                    continue;
                }
                SchemaElementKind kind = kindOf(m.getAttributeName(), strat);
                if (kind == null) {
                    continue;
                }
                List<String> vals = new ArrayList<>();
                for (String v : m.getValues()) {
                    if (isAddNew(kind, v, strat, live)) {
                        vals.add(v);
                    }
                }
                if (!vals.isEmpty()) {
                    kept.add(new Modification(ModificationType.ADD, m.getAttributeName(),
                            vals.toArray(new String[0])));
                }
            }
            return kept.isEmpty() ? null : new LDIFModifyChangeRecord(dn, kept.toArray(new Modification[0]));
        }

        Entry source;
        boolean wasAddRecord;
        if (rec instanceof LDIFAddChangeRecord add) {
            source = add.getEntryToAdd();
            wasAddRecord = true;
        } else if (rec instanceof Entry entry) {
            source = entry;
            wasAddRecord = false;
        } else {
            return null;
        }

        Entry filtered = new Entry(dn);
        boolean anySchemaKept = false;
        for (Attribute a : source.getAttributes()) {
            SchemaElementKind kind = kindOf(a.getName(), strat);
            if (kind == null) {
                filtered.addAttribute(a); // structural attrs (objectClass, cn, …) carry through
                continue;
            }
            List<String> vals = new ArrayList<>();
            for (String v : a.getValues()) {
                if (isAddNew(kind, v, strat, live)) {
                    vals.add(v);
                }
            }
            if (!vals.isEmpty()) {
                filtered.addAttribute(a.getName(), vals.toArray(new String[0]));
                anySchemaKept = true;
            }
        }
        if (!anySchemaKept) {
            return null; // no new schema values → don't create/touch the container
        }
        return wasAddRecord ? new LDIFAddChangeRecord(filtered) : filtered;
    }

    /**
     * Whether a raw schema-element value would be an ADD_NEW against the live
     * schema — i.e. its name isn't already defined and its OID isn't already
     * owned. Mirrors {@link #classifyDef}'s ADD_NEW branch for a value already
     * known to be in scope and additive. Unparseable values are not adds.
     */
    private boolean isAddNew(SchemaElementKind kind, String rawValue, SchemaWriteStrategy strat, Schema live) {
        String normalized = strat.normalizeDefinition(rawValue);
        try {
            if (kind == SchemaElementKind.ATTRIBUTE_TYPE) {
                AttributeTypeDefinition def = new AttributeTypeDefinition(normalized);
                if (attributeExistsByName(live, def)) {
                    return false;
                }
                String oid = def.getOID();
                return oid == null || live.getAttributeType(oid) == null;
            }
            ObjectClassDefinition def = new ObjectClassDefinition(normalized);
            if (objectClassExistsByName(live, def)) {
                return false;
            }
            String oid = def.getOID();
            return oid == null || live.getObjectClass(oid) == null;
        } catch (LDAPException e) {
            return false;
        }
    }

    /**
     * A full LDIF dump of the directory's current subschema subentry, for a
     * snapshot before applying changes.
     *
     * @throws LdapOperationException (→ 422) if the directory type is unsupported
     */
    public String exportSchemaLdif(DirectoryConnection dc) {
        strategyResolver.resolve(dc.getDirectoryType());
        Schema schema = schemaService.fetchSchema(dc);
        return schema.getSchemaEntry().toLDIFString();
    }

    // ── Cache management (mirrors LdifPreviewService) ──────────────────────────

    private void store(UUID id, CachedSchemaPreview entry) {
        evictExpired();
        if (cache.size() >= maxCacheEntries) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
        cache.put(id, entry);
    }

    private CachedSchemaPreview require(UUID previewId, UUID ownerId) {
        evictExpired();
        CachedSchemaPreview cp = cache.get(previewId);
        if (cp == null || !cp.ownerId().equals(ownerId)) {
            throw new ResourceNotFoundException("SchemaPreview", previewId);
        }
        return cp;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
        cache.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
