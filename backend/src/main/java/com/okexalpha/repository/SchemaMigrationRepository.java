package com.okexalpha.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** okx_schema_migration：db/migrations/*.sql 的应用账本（id -> 文件校验和）。 */
@Repository
public class SchemaMigrationRepository {

    /** 与 db/schema.sql 第 13 节一致；启动时先建，保证迁移账本先于迁移存在。 */
    private static final String CREATE_LEDGER = """
            CREATE TABLE IF NOT EXISTS okx_schema_migration (
                id          text PRIMARY KEY,
                checksum    text NOT NULL,
                duration_ms bigint,
                applied_at  timestamptz NOT NULL DEFAULT now()
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureLedger() {
        jdbcTemplate.execute(CREATE_LEDGER);
    }

    /** 已应用迁移：id -> checksum。 */
    public Map<String, String> applied() {
        Map<String, String> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, checksum FROM okx_schema_migration");
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("id")), String.valueOf(row.get("checksum")));
        }
        return result;
    }

    public void record(String id, String checksum, long durationMs) {
        jdbcTemplate.update("""
                INSERT INTO okx_schema_migration (id, checksum, duration_ms)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET checksum = EXCLUDED.checksum,
                    duration_ms = EXCLUDED.duration_ms,
                    applied_at = now()
                """, id, checksum, durationMs);
    }
}
