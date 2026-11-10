// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncDriftItem;
import com.ldapportal.entity.enums.SyncDriftCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The drift items of each sync set's last complete content verify (only the
 * latest is kept), for the membership dialog's Drift tab: paged, filterable by
 * category and searchable, and streamed for the CSV download. Plain JDBC, like
 * {@link SyncRunEntryStore}: a large set's verify writes its rows in batches.
 */
@Repository
@RequiredArgsConstructor
public class SyncDriftStore {

    /** Rows fetched per round trip while streaming. */
    private static final int FETCH_SIZE = 1000;

    /** Rows per INSERT batch. */
    private static final int BATCH = 1000;

    private static final TypeReference<List<SyncDriftItem.AttributeDiff>> ATTRS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** One stored item: its position in the verify (missing, then orphans, then content drift) and the item. */
    public record Row(int seq, SyncDriftItem item) {
    }

    /** Replace the set's stored items with a complete verify's. */
    @Transactional
    public void replace(UUID syncSetId, List<SyncDriftItem> items) {
        jdbc.update("DELETE FROM sync_verify_entry WHERE sync_set_id = ?", syncSetId);
        for (int from = 0; from < items.size(); from += BATCH) {
            List<SyncDriftItem> chunk = items.subList(from, Math.min(items.size(), from + BATCH));
            int base = from;
            jdbc.batchUpdate("INSERT INTO sync_verify_entry "
                            + "(sync_set_id, seq, category, target_dn, source_dn, identity, attributes) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                            SyncDriftItem it = chunk.get(i);
                            ps.setObject(1, syncSetId);
                            ps.setInt(2, base + i);
                            ps.setString(3, it.category().name());
                            ps.setString(4, it.targetDn());
                            ps.setString(5, it.sourceDn());
                            ps.setString(6, it.identity());
                            ps.setString(7, it.attributes().isEmpty() ? null : json(it.attributes()));
                        }

                        @Override
                        public int getBatchSize() {
                            return chunk.size();
                        }
                    });
        }
    }

    /**
     * One page of the set's items, in verify order, optionally only one
     * {@code category} and only those whose target DN, source DN or differing
     * attributes contain {@code search} (case-insensitive).
     */
    public List<Row> page(UUID syncSetId, SyncDriftCategory category, String search, int offset, int limit) {
        Where w = where(syncSetId, category, search);
        List<Object> args = new ArrayList<>(w.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT seq, category, target_dn, source_dn, identity, attributes FROM sync_verify_entry "
                + w.sql() + " ORDER BY seq LIMIT ? OFFSET ?", rowMapper(), args.toArray());
    }

    /** How many of the set's items match, as {@link #page}. */
    public long count(UUID syncSetId, SyncDriftCategory category, String search) {
        Where w = where(syncSetId, category, search);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sync_verify_entry " + w.sql(), Long.class,
                w.args().toArray());
        return n == null ? 0 : n;
    }

    /** Stored items per category (every category present, zero when none). */
    public Map<SyncDriftCategory, Long> countsByCategory(UUID syncSetId) {
        Map<SyncDriftCategory, Long> out = new EnumMap<>(SyncDriftCategory.class);
        for (SyncDriftCategory c : SyncDriftCategory.values()) {
            out.put(c, 0L);
        }
        jdbc.query("SELECT category, COUNT(*) FROM sync_verify_entry WHERE sync_set_id = ? GROUP BY category",
                rs -> {
                    out.put(SyncDriftCategory.valueOf(rs.getString(1)), rs.getLong(2));
                }, syncSetId);
        return out;
    }

    /** The set's stored items at these positions, in verify order (unknown positions skipped). */
    public List<Row> bySeq(UUID syncSetId, java.util.Collection<Integer> seqs) {
        if (seqs.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(seqs.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(syncSetId);
        args.addAll(seqs);
        return jdbc.query("SELECT seq, category, target_dn, source_dn, identity, attributes FROM sync_verify_entry "
                + "WHERE sync_set_id = ? AND seq IN (" + in + ") ORDER BY seq", rowMapper(), args.toArray());
    }

    /**
     * The set's stored items at these target DNs (compared case-insensitively),
     * in verify order: how a selection made from an older verify, whose
     * positions a newer one has renumbered, finds its entries in the newer one.
     */
    public List<Row> byTargetDn(UUID syncSetId, java.util.Collection<String> targetDns) {
        List<String> dns = targetDns.stream().filter(java.util.Objects::nonNull)
                .map(d -> d.toLowerCase(Locale.ROOT)).distinct().toList();
        if (dns.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(dns.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(syncSetId);
        args.addAll(dns);
        return jdbc.query("SELECT seq, category, target_dn, source_dn, identity, attributes FROM sync_verify_entry "
                + "WHERE sync_set_id = ? AND LOWER(target_dn) IN (" + in + ") ORDER BY seq", rowMapper(), args.toArray());
    }

    /**
     * Drop these stored items (repaired or deleted since the verify found
     * them). Each is matched by position and target DN, so if a newer verify
     * has replaced the set's items meanwhile, its rows at the same positions
     * are left alone.
     */
    public void delete(UUID syncSetId, java.util.Collection<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("DELETE FROM sync_verify_entry WHERE sync_set_id = ? AND seq = ? AND target_dn = ?",
                rows.stream().map(r -> new Object[] {syncSetId, r.seq(), r.item().targetDn()}).toList());
    }

    /** Hand each of the set's items to {@code sink}, in verify order. */
    public void forEach(UUID syncSetId, Consumer<Row> sink) {
        RowMapper<Row> mapper = rowMapper();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT seq, category, target_dn, source_dn, identity, attributes "
                    + "FROM sync_verify_entry WHERE sync_set_id = ? ORDER BY seq");
            ps.setObject(1, syncSetId);
            ps.setFetchSize(FETCH_SIZE);
            return ps;
        }, rs -> {
            sink.accept(mapper.mapRow(rs, rs.getRow()));
        });
    }

    private record Where(String sql, List<Object> args) {
    }

    private static Where where(UUID syncSetId, SyncDriftCategory category, String search) {
        StringBuilder sql = new StringBuilder("WHERE sync_set_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(syncSetId);
        if (category != null) {
            sql.append(" AND category = ?");
            args.add(category.name());
        }
        String like = like(search);
        if (like != null) {
            sql.append(" AND (LOWER(target_dn) LIKE ? OR LOWER(COALESCE(source_dn, '')) LIKE ?"
                    + " OR LOWER(COALESCE(attributes, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return new Where(sql.toString(), args);
    }

    private static String like(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private RowMapper<Row> rowMapper() {
        return (rs, i) -> new Row(rs.getInt(1), new SyncDriftItem(SyncDriftCategory.valueOf(rs.getString(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), attrs(rs.getString(6))));
    }

    private String json(List<SyncDriftItem.AttributeDiff> attrs) {
        try {
            return objectMapper.writeValueAsString(attrs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Couldn't serialise drift attributes", e);
        }
    }

    private List<SyncDriftItem.AttributeDiff> attrs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ATTRS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
