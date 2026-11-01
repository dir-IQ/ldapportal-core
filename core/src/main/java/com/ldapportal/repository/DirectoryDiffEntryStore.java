// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.enums.DirectoryDiffCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A directory diff run's entries ({@code directory_diff_entry}). Plain JDBC: a
 * run appends tens of thousands of rows in batches, and its groups are read a
 * page at a time, or streamed for a CSV export.
 */
@Repository
@RequiredArgsConstructor
public class DirectoryDiffEntryStore {

    /** Rows fetched per round trip while streaming. */
    private static final int FETCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    /**
     * One stored result.
     *
     * @param attributes the attributes that differ (DIFFERENT only)
     * @param detail     the per-attribute differences as JSON (DIFFERENT only)
     */
    public record Row(int seq, DirectoryDiffCategory category, String dnA, String dnB, List<String> attributes,
                      String note, String detail) {
    }

    /** Append rows to a run that is already saved. */
    public void append(UUID runId, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO directory_diff_entry (run_id, seq, category, dn_a, dn_b, attributes, note, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows, rows.size(), (ps, r) -> {
                    ps.setObject(1, runId);
                    ps.setInt(2, r.seq());
                    ps.setString(3, r.category().name());
                    ps.setString(4, r.dnA());
                    ps.setString(5, r.dnB());
                    ps.setString(6, r.attributes().isEmpty() ? null : "," + String.join(",", r.attributes()) + ",");
                    ps.setString(7, r.note());
                    ps.setString(8, r.detail());
                });
    }

    /**
     * One page of a run's {@code category}, in run order: only those whose
     * {@code attribute} differs, and whose DN on either side contains
     * {@code search} (case-insensitive), when given. The detail isn't read.
     */
    public List<Row> page(UUID runId, DirectoryDiffCategory category, String attribute, String search,
                          int offset, int limit) {
        Where where = where(runId, category, attribute, search);
        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT seq, category, dn_a, dn_b, attributes, note FROM directory_diff_entry"
                        + where.sql() + " ORDER BY seq LIMIT ? OFFSET ?",
                (rs, i) -> new Row(rs.getInt(1), DirectoryDiffCategory.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), split(rs.getString(5)), rs.getString(6), null),
                args.toArray());
    }

    /** How many rows {@link #page} would page through. */
    public long count(UUID runId, DirectoryDiffCategory category, String attribute, String search) {
        Where where = where(runId, category, attribute, search);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM directory_diff_entry" + where.sql(), Long.class,
                where.args().toArray());
        return n == null ? 0 : n;
    }

    /** One row, with its detail. */
    public Optional<Row> get(UUID runId, int seq) {
        List<Row> rows = jdbc.query("SELECT seq, category, dn_a, dn_b, attributes, note, detail FROM directory_diff_entry "
                        + "WHERE run_id = ? AND seq = ?",
                (rs, i) -> new Row(rs.getInt(1), DirectoryDiffCategory.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), split(rs.getString(5)), rs.getString(6), rs.getString(7)),
                runId, seq);
        return rows.stream().findFirst();
    }

    /**
     * Hand each of a run's rows in {@code category} (every category when
     * {@code null}) to {@code sink}, in order. Read-only transaction: PostgreSQL
     * only honours the fetch size with autocommit off, so rows really do arrive
     * a batch at a time instead of all at once.
     */
    @Transactional(readOnly = true)
    public void forEach(UUID runId, DirectoryDiffCategory category, Consumer<Row> sink) {
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT seq, category, dn_a, dn_b, attributes, note FROM directory_diff_entry "
                    + "WHERE run_id = ?" + (category == null ? "" : " AND category = ?") + " ORDER BY seq");
            ps.setObject(1, runId);
            if (category != null) {
                ps.setString(2, category.name());
            }
            ps.setFetchSize(FETCH_SIZE);
            return ps;
        }, rs -> {
            sink.accept(new Row(rs.getInt(1), DirectoryDiffCategory.valueOf(rs.getString(2)), rs.getString(3),
                    rs.getString(4), split(rs.getString(5)), rs.getString(6), null));
        });
    }

    /** Remove a run's rows (before the run itself, where no cascade does it). */
    public void deleteRun(UUID runId) {
        jdbc.update("DELETE FROM directory_diff_entry WHERE run_id = ?", runId);
    }

    private record Where(String sql, List<Object> args) {
    }

    private static Where where(UUID runId, DirectoryDiffCategory category, String attribute, String search) {
        StringBuilder sql = new StringBuilder(" WHERE run_id = ? AND category = ?");
        List<Object> args = new ArrayList<>(List.of(runId, category.name()));
        if (attribute != null && !attribute.isBlank()) {
            sql.append(" AND LOWER(attributes) LIKE ? ESCAPE '\\'");
            args.add("%," + escape(attribute.trim().toLowerCase(Locale.ROOT)) + ",%");
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + escape(search.trim().toLowerCase(Locale.ROOT)) + "%";
            sql.append(" AND (LOWER(dn_a) LIKE ? ESCAPE '\\' OR LOWER(dn_b) LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        return new Where(sql.toString(), args);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> split(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String a : attributes.split(",")) {
            if (!a.isBlank()) {
                out.add(a);
            }
        }
        return out;
    }
}
