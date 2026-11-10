// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncHeldReference;
import com.ldapportal.entity.enums.SyncHeldStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The held references of each sync set's last complete content verify (only
 * the latest is kept), for the Drift tab: every one, paged, filterable by
 * status and searchable, and streamed for the CSV download. Plain JDBC, like
 * {@link SyncDriftStore}: a set with many unsynced group members writes its
 * rows in batches.
 */
@Repository
@RequiredArgsConstructor
public class SyncHeldStore {

    /** Rows fetched per round trip while streaming. */
    private static final int FETCH_SIZE = 1000;

    /** Rows per INSERT batch. */
    private static final int BATCH = 1000;

    private static final String COLUMNS = "seq, entry_dn, source_dn, identity, attribute, reference, status";

    private final JdbcTemplate jdbc;

    /** One stored reference and its position in the verify. */
    public record Row(int seq, SyncHeldReference ref) {
    }

    /** Replace the set's stored references with a complete verify's. */
    @Transactional
    public void replace(UUID syncSetId, List<SyncHeldReference> refs) {
        jdbc.update("DELETE FROM sync_verify_held WHERE sync_set_id = ?", syncSetId);
        for (int from = 0; from < refs.size(); from += BATCH) {
            List<SyncHeldReference> chunk = refs.subList(from, Math.min(refs.size(), from + BATCH));
            int base = from;
            jdbc.batchUpdate("INSERT INTO sync_verify_held "
                            + "(sync_set_id, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            SyncHeldReference r = chunk.get(i);
                            ps.setObject(1, syncSetId);
                            ps.setInt(2, base + i);
                            ps.setString(3, r.entryDn());
                            ps.setString(4, r.sourceDn());
                            ps.setString(5, r.identity());
                            ps.setString(6, r.attribute());
                            ps.setString(7, r.reference());
                            ps.setString(8, r.status().name());
                        }

                        @Override
                        public int getBatchSize() {
                            return chunk.size();
                        }
                    });
        }
    }

    /**
     * One page of the set's references, in verify order, optionally only one
     * {@code status} and only those whose entry, source entry, attribute or named
     * DN contains {@code search} (case-insensitive).
     */
    public List<Row> page(UUID syncSetId, SyncHeldStatus status, String search, int offset, int limit) {
        Where w = where(syncSetId, status, search);
        List<Object> args = new ArrayList<>(w.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM sync_verify_held " + w.sql()
                + " ORDER BY seq LIMIT ? OFFSET ?", rowMapper(), args.toArray());
    }

    /** How many of the set's references match, as {@link #page}. */
    public long count(UUID syncSetId, SyncHeldStatus status, String search) {
        Where w = where(syncSetId, status, search);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sync_verify_held " + w.sql(), Long.class,
                w.args().toArray());
        return n == null ? 0 : n;
    }

    /** Stored references per status (every status present, zero when none). */
    public Map<SyncHeldStatus, Long> countsByStatus(UUID syncSetId) {
        Map<SyncHeldStatus, Long> out = new EnumMap<>(SyncHeldStatus.class);
        for (SyncHeldStatus s : SyncHeldStatus.values()) {
            out.put(s, 0L);
        }
        jdbc.query("SELECT status, COUNT(*) FROM sync_verify_held WHERE sync_set_id = ? GROUP BY status",
                rs -> {
                    out.put(SyncHeldStatus.valueOf(rs.getString(1)), rs.getLong(2));
                }, syncSetId);
        return out;
    }

    /** Hand each of the set's references to {@code sink}, in verify order. */
    public void forEach(UUID syncSetId, Consumer<Row> sink) {
        RowMapper<Row> mapper = rowMapper();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM sync_verify_held "
                    + "WHERE sync_set_id = ? ORDER BY seq");
            ps.setObject(1, syncSetId);
            ps.setFetchSize(FETCH_SIZE);
            return ps;
        }, rs -> {
            sink.accept(mapper.mapRow(rs, rs.getRow()));
        });
    }

    private record Where(String sql, List<Object> args) {
    }

    private static Where where(UUID syncSetId, SyncHeldStatus status, String search) {
        StringBuilder sql = new StringBuilder("WHERE sync_set_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(syncSetId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (LOWER(entry_dn) LIKE ? OR LOWER(COALESCE(source_dn, '')) LIKE ?"
                    + " OR LOWER(attribute) LIKE ? OR LOWER(reference) LIKE ?)");
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
        return new Where(sql.toString(), args);
    }

    private static RowMapper<Row> rowMapper() {
        return (rs, i) -> new Row(rs.getInt(1), new SyncHeldReference(rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), SyncHeldStatus.valueOf(rs.getString(7))));
    }
}
