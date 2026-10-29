// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every entry a run changed or flagged, kept when its sync set records all
 * DNs (see {@link SyncReconcileHistory}). Plain JDBC: a large run appends rows
 * in batches, and the CSV download reads them back a page at a time rather
 * than as entities. Rows go with their run ({@code ON DELETE CASCADE}).
 */
@Repository
@RequiredArgsConstructor
public class SyncRunEntryStore {

    /** Rows fetched per round trip while streaming a run's entries. */
    private static final int FETCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    /** One recorded entry: its position in the run, outcome, target DN and (for a failure or review) why. */
    public record Entry(int seq, String outcome, String targetDn, String reason) {
    }

    /** Append entries to a run that is already saved. */
    public void append(UUID runId, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO sync_reconcile_run_entry (run_id, seq, outcome, target_dn, reason) VALUES (?, ?, ?, ?, ?)",
                entries, entries.size(), (ps, e) -> {
                    ps.setObject(1, runId);
                    ps.setInt(2, e.seq());
                    ps.setString(3, e.outcome());
                    ps.setString(4, e.targetDn());
                    ps.setString(5, e.reason());
                });
    }

    /**
     * One page of a run's entries with {@code outcome}, in processing order,
     * optionally only those whose DN contains {@code search} (case-insensitive).
     */
    public List<Entry> page(UUID runId, String outcome, String search, int offset, int limit) {
        String like = like(search);
        String sql = "SELECT seq, outcome, target_dn, reason FROM sync_reconcile_run_entry "
                + "WHERE run_id = ? AND outcome = ?" + (like == null ? "" : " AND LOWER(target_dn) LIKE ?")
                + " ORDER BY seq LIMIT ? OFFSET ?";
        Object[] args = like == null
                ? new Object[] {runId, outcome, limit, offset}
                : new Object[] {runId, outcome, like, limit, offset};
        return jdbc.query(sql,
                (rs, i) -> new Entry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)), args);
    }

    /** How many of a run's entries have {@code outcome} (and match {@code search}, as {@link #page}). */
    public long count(UUID runId, String outcome, String search) {
        String like = like(search);
        String sql = "SELECT COUNT(*) FROM sync_reconcile_run_entry WHERE run_id = ? AND outcome = ?"
                + (like == null ? "" : " AND LOWER(target_dn) LIKE ?");
        Object[] args = like == null ? new Object[] {runId, outcome} : new Object[] {runId, outcome, like};
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static String like(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Hand each of a run's entries to {@code sink}, in processing order. */
    public void forEach(UUID runId, Consumer<Entry> sink) {
        jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT seq, outcome, target_dn, reason FROM sync_reconcile_run_entry WHERE run_id = ? ORDER BY seq");
            ps.setObject(1, runId);
            ps.setFetchSize(FETCH_SIZE);
            return ps;
        }, rs -> {
            sink.accept(new Entry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        });
    }
}
