package com.okexalpha.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 迁移脚本自身：存在、可读、按文件名排序、内容符合预期、校验和稳定。 */
class MigrationScriptsTest {

    private static List<Resource> migrations() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources(SchemaMigrationRunner.LOCATION_PATTERN);
        List<Resource> list = new ArrayList<>(List.of(found));
        list.sort((a, b) -> SchemaMigrationRunner.idOf(a).compareTo(SchemaMigrationRunner.idOf(b)));
        return list;
    }

    @Test
    void shipsOrderedMigrations() throws Exception {
        List<String> ids = migrations().stream().map(SchemaMigrationRunner::idOf).toList();

        assertThat(ids).containsExactly(
                "001_kline_and_funding_types",
                "002_timescale_hypertables",
                "003_timescale_compression",
                "004_timescale_integer_now_function",
                "005_kline_compression_tuning",
                "006_compress_historical_chunks",
                "007_strategy_backtest_columns",
                "008_backtest_result_keys",
                "009_credential_vault");
    }

    @Test
    void compressionTuningMigrationChangesSegmentByAndRecompresses() throws Exception {
        String sql = text("db/migrations/005_kline_compression_tuning.sql").toLowerCase(Locale.ROOT);

        // 实测结论：segmentby 用 inst_id 会把 chunk 切成几百个极窄 segment，压缩后反而变大
        assertThat(sql).contains("compress_segmentby = ''bar,kline_type''");
        assertThat(sql).contains("compress_orderby = ''inst_id, ts_ms desc''");
        // 改设置前必须先把已压缩 chunk 全部解压
        assertThat(sql).contains("decompress_chunk");
        assertThat(sql).contains("okx_kline");
    }

    @Test
    void historicalChunkMigrationUsesShowChunksWithAbsoluteCutoff() throws Exception {
        String sql = text("db/migrations/006_compress_historical_chunks.sql").toLowerCase(Locale.ROOT);

        // bigint 时间维度的 chunks 视图 range_start/range_end 恒为 NULL，只能用 show_chunks 按毫秒筛
        assertThat(sql).contains("show_chunks('public.okx_kline', older_than => cutoff)");
        assertThat(sql).contains("okx_now_ms() - 2592000000");
        assertThat(sql).contains("is_compressed is not true"); // 幂等：已压缩的 chunk 跳过
        assertThat(sql).contains("compress_chunk");
    }

    @Test
    void integerNowFunctionMigrationBindsBigintDimension() throws Exception {
        String sql = text("db/migrations/004_timescale_integer_now_function.sql").toLowerCase(Locale.ROOT);

        // bigint 时间维度必须绑定 integer now 函数，否则压缩策略每天报 "integer_now function not set"
        assertThat(sql).contains("create or replace function okx_now_ms");
        assertThat(sql).contains("set_integer_now_func");
        assertThat(sql).contains("okx_kline");
        assertThat(sql).contains("okx_funding_rate");
    }

    @Test
    void typeMigrationConvertsToDoublePrecisionAndBoolean() throws Exception {
        String sql = text("db/migrations/001_kline_and_funding_types.sql").toLowerCase(Locale.ROOT);

        assertThat(sql).contains("double precision");
        assertThat(sql).contains("boolean");
        assertThat(sql).contains("okx_kline");
        assertThat(sql).contains("okx_funding_rate");
        assertThat(sql).contains("information_schema.columns");
    }

    @Test
    void hypertableMigrationIsGuardedByExtensionAvailability() throws Exception {
        String sql = text("db/migrations/002_timescale_hypertables.sql").toLowerCase(Locale.ROOT);

        assertThat(sql).contains("pg_available_extensions");
        assertThat(sql).contains("create extension if not exists timescaledb");
        assertThat(sql).contains("create_hypertable");
        assertThat(sql).contains("604800000");
        assertThat(sql).contains("drop index if exists idx_okx_kline_ts_brin");
    }

    @Test
    void compressionMigrationUsesBigintIntervalAndSegmentBy() throws Exception {
        String sql = text("db/migrations/003_timescale_compression.sql").toLowerCase(Locale.ROOT);

        assertThat(sql).contains("timescaledb.compress_segmentby");
        assertThat(sql).contains("timescaledb.compress_orderby");
        assertThat(sql).contains("add_compression_policy");
        assertThat(sql).contains("2592000000");
        assertThat(sql).contains("autovacuum_vacuum_scale_factor");
    }

    @Test
    void schemaDeclaresMigrationLedger() throws Exception {
        String sql = text("db/schema.sql").toLowerCase(Locale.ROOT);

        assertThat(sql).contains("okx_schema_migration");
        assertThat(sql).contains("checksum");
        assertThat(sql).doesNotContain("idx_okx_kline_ts_brin");
    }

    @Test
    void idStripsSqlSuffixAndChecksumMatchesContent() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migrations/001_kline_and_funding_types.sql");

        assertThat(SchemaMigrationRunner.idOf(resource)).isEqualTo("001_kline_and_funding_types");

        String checksum = SchemaMigrationRunner.checksum(resource);
        assertThat(checksum).hasSize(64).matches("[0-9a-f]{64}");
        // 同一内容必须得到同一个校验和（换机器/换 JVM 也一样）
        assertThat(SchemaMigrationRunner.checksum(new ClassPathResource("db/migrations/001_kline_and_funding_types.sql")))
                .isEqualTo(checksum);

        byte[] bytes = resource.getInputStream().readAllBytes();
        String expected = HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(checksum).isEqualTo(expected);
    }

    private static String text(String classpathLocation) throws Exception {
        return new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
    }
}
