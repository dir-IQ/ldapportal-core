// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.util.CsvUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    /** Rows one export writes at most; a larger match is cut short (see {@link ExportResult#truncated}). */
    public static final int MAX_EXPORT_ROWS = 50_000;

    /** Rows an export reads per query. */
    static final int EXPORT_PAGE_SIZE = 1_000;

    /** The export's columns, in order. */
    static final List<String> EXPORT_HEADERS = List.of(
            "occurredAt", "action", "directory", "targetDn", "actor", "actorType", "source", "operation",
            "attributes", "correlationId", "origin", "detail");

    private final AuditEventRepository auditRepo;
    private final ObjectMapper objectMapper;

    /**
     * What an export wrote.
     *
     * @param written   rows written
     * @param matched   rows the filters matched
     * @param truncated whether more matched than {@link #MAX_EXPORT_ROWS}, so the oldest were left out
     */
    public record ExportResult(int written, long matched, boolean truncated) {
    }

    /**
     * Paginated, multi-filter query. Filters are carried in
     * {@link AuditQueryCriteria} (all optional); the {@code source}
     * field matches the {@code detail.source} key — the per-source
     * convention used by the IVIA verbs, profile changes, etc.
     * ({@code source = "ivia"} narrows to IVIA-only events without the
     * client having to download other events to filter them out).
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> query(AuditQueryCriteria criteria, int page, int size) {
        PageRequest pageable = PageRequest.of(page, clampSize(size));
        return auditRepo.findAll(
                        criteria.directoryId(), criteria.actorId(), actionNames(criteria.actions()),
                        criteria.targetDn(), criteria.source(), criteria.correlationId(),
                        criteria.from(), criteria.to(), pageable)
                .map(AuditEventResponse::from);
    }

    /**
     * Queries audit events restricted to a set of authorized directories.
     * Used for non-superadmins who haven't specified a directoryId filter.
     * {@link AuditQueryCriteria#directoryId()} is ignored here — the
     * {@code directoryIds} set is the directory scope.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> queryForDirectories(
            Set<UUID> directoryIds, AuditQueryCriteria criteria, int page, int size) {
        PageRequest pageable = PageRequest.of(page, clampSize(size));
        return auditRepo.findAllByDirectoryIds(
                        directoryIds, criteria.actorId(), actionNames(criteria.actions()),
                        criteria.targetDn(), criteria.source(), criteria.correlationId(),
                        criteria.from(), criteria.to(), pageable)
                .map(AuditEventResponse::from);
    }

    /**
     * Every event the filters match, newest first, as CSV: the query the log
     * shows, without its page cap, up to {@link #MAX_EXPORT_ROWS} rows. With
     * no {@code to}, the window ends when the export starts, so events
     * recorded while it reads don't shift its pages.
     *
     * <p>Columns ({@link #EXPORT_HEADERS}): when it happened, the action
     * ({@code entry.update}), the directory's name, the target DN, the actor
     * (blank for the sync engine's writes), its type, {@code detail.source}
     * ({@code directory-sync}, {@code ivia}, …), {@code detail.operation}, the
     * attributes an update changed ({@code detail.attributes}, {@code |}-joined),
     * the correlation id, where the event was recorded from (portal or
     * changelog), and the whole detail as JSON.
     *
     * @param directoryIds the directories a non-superadmin may read, or {@code null}
     *                     to use {@link AuditQueryCriteria#directoryId()} as is
     */
    @Transactional(readOnly = true)
    public ExportResult exportCsv(AuditQueryCriteria criteria, Set<UUID> directoryIds, Writer out) {
        AuditQueryCriteria pinned = criteria.to() != null ? criteria
                : AuditQueryCriteria.builder().directoryId(criteria.directoryId()).actorId(criteria.actorId())
                        .actions(criteria.actions()).targetDn(criteria.targetDn()).source(criteria.source())
                        .correlationId(criteria.correlationId()).from(criteria.from())
                        .to(OffsetDateTime.now()).build();
        try {
            CsvUtils.writeHeader(out, EXPORT_HEADERS);
            int written = 0;
            long matched = 0;
            for (int page = 0; written < MAX_EXPORT_ROWS; page++) {
                Page<AuditEvent> rows = exportPage(pinned, directoryIds, page);
                matched = rows.getTotalElements();
                for (AuditEvent e : rows.getContent()) {
                    if (written >= MAX_EXPORT_ROWS) {
                        break;
                    }
                    CsvUtils.writeRow(out, EXPORT_HEADERS, exportRow(e));
                    written++;
                }
                if (!rows.hasNext()) {
                    break;
                }
            }
            out.flush();
            return new ExportResult(written, matched, matched > written);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Page<AuditEvent> exportPage(AuditQueryCriteria c, Set<UUID> directoryIds, int page) {
        PageRequest pageable = PageRequest.of(page, EXPORT_PAGE_SIZE);
        String actions = actionNames(c.actions());
        return directoryIds != null
                ? auditRepo.findAllByDirectoryIds(directoryIds, c.actorId(), actions, c.targetDn(), c.source(),
                        c.correlationId(), c.from(), c.to(), pageable)
                : auditRepo.findAll(c.directoryId(), c.actorId(), actions, c.targetDn(), c.source(),
                        c.correlationId(), c.from(), c.to(), pageable);
    }

    private Map<String, String> exportRow(AuditEvent e) {
        Map<String, Object> detail = e.getDetail() != null ? e.getDetail() : Map.of();
        Map<String, String> row = new HashMap<>();
        row.put("occurredAt", e.getOccurredAt() != null ? e.getOccurredAt().toString() : "");
        row.put("action", e.getAction() != null ? e.getAction().getDbValue() : "");
        row.put("directory", e.getDirectoryName());
        row.put("targetDn", e.getTargetDn());
        row.put("actor", e.getActorUsername());
        row.put("actorType", e.getActorType());
        row.put("source", text(detail.get("source")));
        row.put("operation", text(detail.get("operation")));
        row.put("attributes", detail.get("attributes") instanceof List<?> names
                ? String.join(String.valueOf(CsvUtils.MULTI_VALUE_SEPARATOR),
                        names.stream().map(String::valueOf).toList())
                : "");
        row.put("correlationId", e.getCorrelationId() != null ? e.getCorrelationId().toString() : "");
        row.put("origin", e.getSource() != null ? e.getSource().name() : "");
        try {
            row.put("detail", detail.isEmpty() ? "" : objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException ex) {
            row.put("detail", "");
        }
        return row;
    }

    private static String text(Object v) {
        return v != null ? String.valueOf(v) : "";
    }

    /**
     * Renders the optional action filter as a comma-joined list of audit-action
     * names (e.g. {@code "USER_CREATE,USER_UPDATE"}) for the repository's
     * {@code string_to_array(...) = ANY} match, or {@code null} when no action
     * filter is set.
     *
     * <p><strong>Names, not dbValues.</strong> {@code AuditEvent.action} is mapped
     * {@code @Enumerated(EnumType.STRING)}, which takes precedence over the
     * autoApply {@code AuditActionConverter} (JPA does not apply converters to
     * {@code @Enumerated} attributes). The persisted column therefore holds the
     * enum {@link Enum#name()} ({@code "USER_CREATE"}), not the
     * {@code getDbValue()} dot-notation ({@code "user.create"}). Filtering on
     * dbValues silently matched nothing — this must match on names. Enum names
     * never contain commas, so a comma delimiter is unambiguous.
     */
    private static String actionNames(List<AuditAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.stream()
                .filter(java.util.Objects::nonNull)
                .map(AuditAction::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private int clampSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
