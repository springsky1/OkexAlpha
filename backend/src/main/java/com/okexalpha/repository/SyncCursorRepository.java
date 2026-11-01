package com.okexalpha.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** okx_sync_cursor：增量同步水位。 */
@Repository
public class SyncCursorRepository {

    private final JdbcTemplate jdbcTemplate;

    public SyncCursorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long get(String cursorKey) {
        var rows = jdbcTemplate.queryForList(
                "SELECT last_ts_ms FROM okx_sync_cursor WHERE cursor_key = ?", Long.class, cursorKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void set(String cursorKey, long lastTsMs) {
        jdbcTemplate.update("""
                INSERT INTO okx_sync_cursor (cursor_key, last_ts_ms, updated_at) VALUES (?, ?, now())
                ON CONFLICT (cursor_key) DO UPDATE SET last_ts_ms = EXCLUDED.last_ts_ms, updated_at = now()
                """, cursorKey, lastTsMs);
    }

    public java.util.Map<String, Long> all() {
        java.util.Map<String, Long> map = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("SELECT cursor_key, last_ts_ms FROM okx_sync_cursor ORDER BY cursor_key",
                rs -> {
                    map.put(rs.getString("cursor_key"), rs.getObject("last_ts_ms") == null ? null : rs.getLong("last_ts_ms"));
                });
        return map;
    }
}
