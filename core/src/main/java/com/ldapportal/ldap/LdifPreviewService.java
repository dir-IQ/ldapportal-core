// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.LdifMemberDelta;
import com.ldapportal.dto.ldap.LdifPreviewIssue;
import com.ldapportal.dto.ldap.LdifPreviewOp;
import com.ldapportal.dto.ldap.LdifPreviewPage;
import com.ldapportal.dto.ldap.LdifPreviewRow;
import com.ldapportal.dto.ldap.LdifPreviewRowDetail;
import com.ldapportal.dto.ldap.LdifPreviewSummary;
import com.ldapportal.dto.ldap.LdifPreviewSummary.OpCounts;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdifService.ParsedRecord;
import com.ldapportal.ldap.validation.DnValidator;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only, stateful LDIF <em>preview</em>: parse an upload once, classify
 * every record (add / modify / delete / moddn / skip / error), detect
 * conflicts against the live directory with <em>batched</em> existence checks,
 * flag DN-syntax / in-scope issues, and compute group member deltas. The
 * result is cached under a short-lived {@code previewId} (per-superadmin, TTL'd)
 * so a 2–5K import is parsed and existence-checked once, then paged/filtered
 * cheaply — and {@link #apply} runs the <em>exact</em> records previewed.
 *
 * <p>Superadmin-only is enforced by the controller; this service is read-only
 * except {@link #apply}, which delegates to {@link LdifService}.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LdifPreviewService {

    private final LdapConnectionFactory connectionFactory;
    private final LdifService ldifService;

    @Value("${ldapportal.ldif.preview.max-records:50000}")
    private int maxRecords;
    @Value("${ldapportal.ldif.preview.ttl-minutes:30}")
    private long ttlMinutes;
    @Value("${ldapportal.ldif.preview.max-entries:20}")
    private int maxCacheEntries;
    @Value("${ldapportal.ldif.preview.max-values-per-attr:200}")
    private int maxValuesPerAttr;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final Set<String> MEMBER_ATTRS = Set.of("member", "uniquemember", "memberuid");

    private final Map<UUID, CachedPreview> cache = new ConcurrentHashMap<>();

    /** A computed preview held in memory until applied or expired. */
    private record CachedPreview(UUID ownerId,
                                 UUID directoryId,
                                 ConflictHandling conflict,
                                 Instant createdAt,
                                 List<ParsedRecord> records,
                                 List<LdifPreviewRow> rows,
                                 OpCounts counts,
                                 int warningCount,
                                 int errorCount) {}

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Parse + classify the upload, run batched existence checks, cache the
     * result, and return the summary (incl. the first page of rows).
     *
     * @throws IllegalArgumentException if the upload exceeds the record cap (→ 400)
     */
    public LdifPreviewSummary createPreview(DirectoryConnection dc,
                                            InputStream upload,
                                            ConflictHandling conflict,
                                            UUID ownerId) {
        List<ParsedRecord> records = ldifService.parse(upload);
        if (records.size() > maxRecords) {
            throw new IllegalArgumentException(
                    "LDIF has " + records.size() + " records, exceeding the preview limit of "
                            + maxRecords + ". Split the file or raise ldapportal.ldif.preview.max-records.");
        }

        boolean entra = dc.getDirectoryType() == DirectoryType.ENTRA_ID;
        String baseDnNorm = normDn(dc.getBaseDn());
        // Group classification uses the directory's configured (or vendor-
        // default) group object classes, so an AD `group` or IBM
        // `groupOfURLs` is recognised here exactly as it is in search.
        Set<String> groupOcs = com.ldapportal.entity.DirectoryObjectClassDefaults
                .effectiveGroupObjectClassSet(dc);
        // User classification mirrors the importer: a new user-entry add is
        // routed through the provisioning SPI (so a vendor interceptor can
        // augment it). Same configured user object classes the apply path uses.
        Set<String> userOcs = com.ldapportal.entity.DirectoryObjectClassDefaults
                .effectiveUserObjectClassSet(dc);
        // File-level safety mirror: if the upload already contains vendor-overlay
        // entries (secUser), the importer writes everything as-is and provisions
        // no fresh overlay — so surface that so the UI can say provisioning is
        // suppressed for the whole import.
        boolean containsVendorOverlayEntries = records.stream()
                .map(LdifPreviewService::entryOfRecord)
                .anyMatch(e -> e != null && hasVendorOverlayObjectClass(e));

        // Distinct target DNs of content (non-change) entries — the only records
        // whose classification depends on whether they already exist.
        Set<String> contentDns = new HashSet<>();
        for (ParsedRecord pr : records) {
            if (!pr.isError() && pr.record() instanceof Entry e) {
                String n = normDn(e.getDN());
                if (n != null) contentDns.add(n);
            }
        }
        Set<String> existing = entra ? Set.of() : existingDns(dc, contentDns);

        List<LdifPreviewRow> rows = new ArrayList<>(records.size());
        int add = 0, modify = 0, delete = 0, moddn = 0, skip = 0, error = 0, warnings = 0, errors = 0;
        int userAddCount = 0, applicable = 0, outOfScope = 0;
        for (ParsedRecord pr : records) {
            LdifPreviewRow row = classify(pr, conflict, existing, baseDnNorm, entra, groupOcs, userOcs);
            rows.add(row);
            if (row.userAdd()) userAddCount++;
            switch (row.op()) {
                case ADD -> add++;
                case MODIFY -> modify++;
                case DELETE -> delete++;
                case MODDN -> moddn++;
                case SKIP -> skip++;
                case ERROR -> error++;
            }
            boolean hasWarning = row.issues().stream().anyMatch(i -> LdifPreviewIssue.WARNING.equals(i.severity()));
            if (isBlocked(row)) errors++;
            if (hasWarning) warnings++;
            if (isApplicable(row)) applicable++;
            if (row.issues().stream().anyMatch(i -> "OUT_OF_SCOPE".equals(i.code()))) outOfScope++;
        }

        OpCounts counts = new OpCounts(add, modify, delete, moddn, skip, error);
        UUID previewId = UUID.randomUUID();
        store(previewId, new CachedPreview(ownerId, dc.getId(), conflict, Instant.now(),
                records, rows, counts, warnings, errors));

        LdifPreviewPage page0 = slice(rows, null, null, 0, DEFAULT_PAGE_SIZE);
        log.info("LDIF preview {} for dir {}: {} rows (add={} modify={} delete={} moddn={} skip={} error={}, "
                        + "warn={}, applicable={}, outOfScope={})",
                previewId, dc.getId(), rows.size(), add, modify, delete, moddn, skip, error,
                warnings, applicable, outOfScope);
        return new LdifPreviewSummary(previewId.toString(), rows.size(), counts,
                warnings, errors, false, page0, userAddCount, containsVendorOverlayEntries,
                applicable, outOfScope, dc.getBaseDn());
    }

    // ── Page / detail / apply ────────────────────────────────────────────────

    /** Filtered/searched/paged slice of a cached preview's rows. */
    public LdifPreviewPage page(UUID previewId, UUID ownerId, String op, String q, int page, int size) {
        CachedPreview cp = require(previewId, ownerId);
        return slice(cp.rows(), op, q, page, size <= 0 ? DEFAULT_PAGE_SIZE : size);
    }

    /** Full attributes (capped) for one previewed record. */
    public LdifPreviewRowDetail rowDetail(UUID previewId, UUID ownerId, int rowNumber) {
        CachedPreview cp = require(previewId, ownerId);
        if (rowNumber < 1 || rowNumber > cp.records().size()) {
            throw new ResourceNotFoundException("LdifPreviewRow", rowNumber);
        }
        ParsedRecord pr = cp.records().get(rowNumber - 1);
        LdifPreviewRow row = cp.rows().get(rowNumber - 1);
        return new LdifPreviewRowDetail(rowNumber, row.dn(), row.op(),
                attributesOf(pr), row.memberDelta(), row.issues());
    }

    /**
     * Apply the previewed records via the existing apply path, then evict.
     * {@code excludeOverlayRows} carries the 1-based row numbers the operator
     * opted out of vendor (secUser) provisioning for in the preview.
     */
    public LdifImportResult apply(UUID previewId, UUID ownerId, DirectoryConnection dc,
                                  boolean suppressVendorOverlay,
                                  java.util.Set<Integer> excludeOverlayRows) {
        CachedPreview cp = require(previewId, ownerId);
        // Block-on-apply: never send a row the preview flagged as a blocking error
        // (parse error, invalid DN, or a DN outside the directory base) to the
        // server — it could only ever be rejected. cp.rows() is index-parallel to
        // cp.records(); SKIP / conflict rows are not blocked and still flow through,
        // so their outcomes are reported exactly as before.
        List<ParsedRecord> records = cp.records();
        List<LdifPreviewRow> rows = cp.rows();
        List<ParsedRecord> applicable = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            if (!isBlocked(rows.get(i))) applicable.add(records.get(i));
        }
        LdifImportResult result = ldifService.applyParsedRecords(
                dc, applicable, cp.conflict(), suppressVendorOverlay, excludeOverlayRows);
        cache.remove(previewId);
        return result;
    }

    /** Conflict mode the preview was computed with (used for the apply audit record). */
    public ConflictHandling conflictOf(UUID previewId, UUID ownerId) {
        return require(previewId, ownerId).conflict();
    }

    // ── Classification ────────────────────────────────────────────────────────

    private LdifPreviewRow classify(ParsedRecord pr, ConflictHandling conflict,
                                    Set<String> existing, String baseDnNorm, boolean entra,
                                    Set<String> groupOcs, Set<String> userOcs) {
        if (pr.isError()) {
            return new LdifPreviewRow(pr.rowNumber(), null, LdifPreviewOp.ERROR,
                    List.of(), 0, null, null, List.of(LdifPreviewIssue.parseError(pr.parseError())), false);
        }

        LDIFRecord record = pr.record();
        String dn = record.getDN();
        List<LdifPreviewIssue> issues = new ArrayList<>();

        boolean validDn = !entra && DnValidator.isValidDn(dn);
        if (!entra && !validDn) {
            issues.add(LdifPreviewIssue.invalidDn(dn));
        } else if (!entra && baseDnNorm != null && !baseDnNorm.isEmpty() && !isUnder(dn, dc(baseDnNorm))) {
            issues.add(LdifPreviewIssue.outOfScope(baseDnNorm));
        }

        LdifPreviewOp op;
        List<String> objectClasses = List.of();
        int attrCount = 0;
        LdifMemberDelta memberDelta = null;
        Integer memberCount = null;
        Entry addEntry = null; // the entry being added, for user-add detection

        if (record instanceof LDIFChangeRecord change) {
            if (change instanceof LDIFAddChangeRecord addRec) {
                op = LdifPreviewOp.ADD;
                Entry e = addRec.getEntryToAdd();
                addEntry = e;
                objectClasses = objectClassesOf(e);
                attrCount = e.getAttributes().size();
                memberCount = groupMemberCount(e, groupOcs);
            } else if (change instanceof LDIFModifyChangeRecord modRec) {
                op = LdifPreviewOp.MODIFY;
                attrCount = modRec.getModifications().length;
                memberDelta = memberDelta(modRec.getModifications());
            } else if (change instanceof LDIFDeleteChangeRecord) {
                op = LdifPreviewOp.DELETE;
            } else if (change instanceof LDIFModifyDNChangeRecord) {
                op = LdifPreviewOp.MODDN;
            } else {
                op = LdifPreviewOp.MODIFY;
            }
        } else if (record instanceof Entry entry) {
            addEntry = entry;
            objectClasses = objectClassesOf(entry);
            attrCount = entry.getAttributes().size();
            memberCount = groupMemberCount(entry, groupOcs);
            boolean exists = validDn && existing.contains(normDn(dn));
            if (exists) {
                issues.add(LdifPreviewIssue.conflictExists());
                op = conflict == ConflictHandling.OVERWRITE ? LdifPreviewOp.MODIFY : LdifPreviewOp.SKIP;
            } else {
                op = LdifPreviewOp.ADD;
            }
        } else {
            op = LdifPreviewOp.SKIP;
        }

        // A new user-entry add routed through the provisioning SPI: only true
        // ADDs of a user entry that doesn't already carry the vendor overlay.
        boolean userAdd = op == LdifPreviewOp.ADD
                && addEntry != null
                && isUserEntry(addEntry, userOcs)
                && !hasVendorOverlayObjectClass(addEntry);

        return new LdifPreviewRow(pr.rowNumber(), dn, op, objectClasses, attrCount,
                memberDelta, memberCount, issues, userAdd);
    }

    /**
     * A row an apply will actually attempt: an actionable op (add / modify /
     * delete / moddn) with no blocking error. SKIP rows (deliberate no-ops) and
     * error rows are excluded. Mirrors the Import button's count.
     */
    private static boolean isApplicable(LdifPreviewRow row) {
        return switch (row.op()) {
            case ADD, MODIFY, DELETE, MODDN -> !isBlocked(row);
            default -> false;
        };
    }

    /**
     * A row the importer must not send to the server: a parse / structural error,
     * an invalid DN, or a DN outside the directory base — all {@code ERROR}
     * severity. {@link #apply} filters these out so a doomed write is never
     * attempted, and {@code createPreview} counts them as errors (not warnings).
     */
    private static boolean isBlocked(LdifPreviewRow row) {
        return row.op() == LdifPreviewOp.ERROR
                || row.issues().stream().anyMatch(i -> LdifPreviewIssue.ERROR.equals(i.severity()));
    }

    private static Entry entryOfRecord(ParsedRecord pr) {
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

    /**
     * Whether the entry already carries the vendor overlay object class
     * ({@code secUser}) — a self-describing export entry the importer writes
     * as-is rather than layering a fresh overlay onto.
     */
    private static boolean hasVendorOverlayObjectClass(Entry entry) {
        String[] ocs = entry.getObjectClassValues();
        if (ocs == null) return false;
        for (String oc : ocs) {
            if ("secuser".equals(oc.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static List<String> objectClassesOf(Entry entry) {
        String[] ocs = entry.getObjectClassValues();
        return ocs == null ? List.of() : Arrays.asList(ocs);
    }

    private static boolean isGroup(Entry entry, Set<String> groupOcs) {
        String[] ocs = entry.getObjectClassValues();
        if (ocs == null) return false;
        for (String oc : ocs) {
            if (groupOcs.contains(oc.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static Integer groupMemberCount(Entry entry, Set<String> groupOcs) {
        if (!isGroup(entry, groupOcs)) return null;
        int count = 0;
        for (String a : MEMBER_ATTRS) {
            Attribute attr = entry.getAttribute(a);
            if (attr != null) count += attr.size();
        }
        return count;
    }

    /** Net member change from a modify's modifications; null if no member attr is touched. */
    private static LdifMemberDelta memberDelta(Modification[] mods) {
        int added = 0, removed = 0;
        boolean touched = false;
        for (Modification m : mods) {
            if (!MEMBER_ATTRS.contains(m.getAttributeName().toLowerCase(Locale.ROOT))) continue;
            touched = true;
            int n = m.getValues().length;
            ModificationType t = m.getModificationType();
            if (ModificationType.DELETE.equals(t)) {
                removed += n; // a value-less delete clears all members; count is unknown (0 shown)
            } else {
                // ADD and REPLACE both raise the member set by the listed values.
                added += n;
            }
        }
        return touched ? new LdifMemberDelta(added, removed) : null;
    }

    private Map<String, List<String>> attributesOf(ParsedRecord pr) {
        if (pr.isError()) return Map.of();
        LDIFRecord record = pr.record();
        Entry entry = null;
        if (record instanceof Entry e) {
            entry = e;
        } else if (record instanceof LDIFAddChangeRecord addRec) {
            entry = addRec.getEntryToAdd();
        } else if (record instanceof LDIFModifyChangeRecord modRec) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Modification m : modRec.getModifications()) {
                out.merge(m.getAttributeName(), cap(Arrays.asList(m.getValues())),
                        (a, b) -> { List<String> merged = new ArrayList<>(a); merged.addAll(b); return cap(merged); });
            }
            return out;
        }
        if (entry == null) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Attribute attr : entry.getAttributes()) {
            out.put(attr.getBaseName(), cap(Arrays.asList(attr.getValues())));
        }
        return out;
    }

    /** Cap a multi-valued attribute so a giant group can't blow up the payload (D3). */
    private List<String> cap(List<String> values) {
        if (values.size() <= maxValuesPerAttr) return values;
        List<String> capped = new ArrayList<>(values.subList(0, maxValuesPerAttr));
        capped.add("… and " + (values.size() - maxValuesPerAttr) + " more");
        return capped;
    }

    // ── Batched existence (the 2–5K round-trip problem) ────────────────────────

    /**
     * Resolve which of {@code targetDnsNorm} already exist, grouping by parent
     * and issuing one one-level (DN-only) search per parent — bulk LDIFs cluster
     * under a few OUs, so thousands of lookups collapse to a handful.
     */
    private Set<String> existingDns(DirectoryConnection dc, Set<String> targetDnsNorm) {
        if (targetDnsNorm.isEmpty()) return Set.of();
        Map<String, Set<String>> byParent = new HashMap<>();
        for (String childNorm : targetDnsNorm) {
            try {
                DN parent = new DN(childNorm).getParent();
                String parentNorm = parent == null ? "" : parent.toNormalizedString();
                byParent.computeIfAbsent(parentNorm, k -> new HashSet<>()).add(childNorm);
            } catch (LDAPException e) {
                // unparseable DN — classified as INVALID_DN elsewhere, never "exists"
            }
        }
        Set<String> existing = new HashSet<>();
        int pageSize = Math.max(100, dc.getPagingSize());
        connectionFactory.withConnection(dc, conn -> {
            for (String parent : byParent.keySet()) {
                if (parent.isEmpty()) continue;
                try {
                    ASN1OctetString cookie = null;
                    do {
                        SearchRequest req = new SearchRequest(parent, SearchScope.ONE,
                                Filter.createPresenceFilter("objectClass"), "1.1");
                        req.addControl(new SimplePagedResultsControl(pageSize, cookie));
                        SearchResult result = conn.search(req);
                        for (SearchResultEntry e : result.getSearchEntries()) {
                            existing.add(normDn(e.getDN()));
                        }
                        SimplePagedResultsControl resp = SimplePagedResultsControl.get(result);
                        cookie = (resp != null && resp.moreResultsToReturn()) ? resp.getCookie() : null;
                    } while (cookie != null && cookie.getValue().length > 0);
                } catch (LDAPException e) {
                    if (!(e instanceof LDAPSearchException se
                            && se.getResultCode() == ResultCode.NO_SUCH_OBJECT)) {
                        log.warn("Preview existence search under '{}' failed: {}", parent, e.getMessage());
                    }
                    // parent absent → none of its children exist (all ADD)
                }
            }
            return null;
        });
        existing.retainAll(targetDnsNorm);
        return existing;
    }

    // ── Filtering / paging ──────────────────────────────────────────────────

    private LdifPreviewPage slice(List<LdifPreviewRow> all, String op, String q, int page, int size) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<LdifPreviewRow> filtered = new ArrayList<>();
        for (LdifPreviewRow r : all) {
            if (!matchesOp(r, op)) continue;
            if (needle != null && !needle.isEmpty()
                    && (r.dn() == null || !r.dn().toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            filtered.add(r);
        }
        int total = filtered.size();
        int from = Math.max(0, page) * size;
        if (from >= total) return new LdifPreviewPage(List.of(), Math.max(0, page), size, total);
        int to = Math.min(from + size, total);
        return new LdifPreviewPage(List.copyOf(filtered.subList(from, to)), Math.max(0, page), size, total);
    }

    private static boolean matchesOp(LdifPreviewRow r, String op) {
        if (op == null || op.isBlank() || "ALL".equalsIgnoreCase(op)) return true;
        return switch (op.toUpperCase(Locale.ROOT)) {
            case "ADD" -> r.op() == LdifPreviewOp.ADD;
            case "MODIFY" -> r.op() == LdifPreviewOp.MODIFY;
            case "DELETE" -> r.op() == LdifPreviewOp.DELETE;
            case "MODDN" -> r.op() == LdifPreviewOp.MODDN;
            case "SKIP" -> r.op() == LdifPreviewOp.SKIP;
            case "CONFLICTS" -> r.issues().stream().anyMatch(i -> "CONFLICT_EXISTS".equals(i.code()));
            case "WARNINGS" -> r.issues().stream().anyMatch(i -> LdifPreviewIssue.WARNING.equals(i.severity()));
            case "ERRORS" -> r.op() == LdifPreviewOp.ERROR
                    || r.issues().stream().anyMatch(i -> LdifPreviewIssue.ERROR.equals(i.severity()));
            default -> true;
        };
    }

    // ── Cache management ──────────────────────────────────────────────────────

    private void store(UUID id, CachedPreview entry) {
        evictExpired();
        if (cache.size() >= maxCacheEntries) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().createdAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
        cache.put(id, entry);
    }

    private CachedPreview require(UUID previewId, UUID ownerId) {
        evictExpired();
        CachedPreview cp = cache.get(previewId);
        if (cp == null || !cp.ownerId().equals(ownerId)) {
            throw new ResourceNotFoundException("LdifPreview", previewId);
        }
        return cp;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
        cache.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    // ── DN helpers ──────────────────────────────────────────────────────────

    private static String normDn(String dn) {
        if (dn == null || dn.isBlank()) return "";
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return null; // unparseable — never matches an existing DN
        }
    }

    private static DN dc(String normalizedBaseDn) {
        try {
            return new DN(normalizedBaseDn);
        } catch (LDAPException e) {
            return null;
        }
    }

    private static boolean isUnder(String dn, DN base) {
        if (base == null) return true; // unknown base → don't flag
        try {
            return new DN(dn).isDescendantOf(base, true);
        } catch (LDAPException e) {
            return true; // invalid DN is flagged separately
        }
    }
}
