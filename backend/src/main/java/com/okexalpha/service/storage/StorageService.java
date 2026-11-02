package com.okexalpha.service.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 统计库/表/分块/压缩体积，供管理页与运维脚本查看"存储是否按预期压缩"。
 *
 * <p>没装 TimescaleDB 的库也能跑：{@code timescale.installed=false}，只返回普通表体积。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private static final String TABLES_SQL = """
            SELECT c.relname,
                   -- pg_table_size 把 TOAST 也算进来：压缩后的时序数据主要躺在 chunk 的 TOAST 里，
                   -- 用 pg_relation_size 会严重低估（实测 okx_kline 的 heap 只有几 MB，TOAST 才是大头）
                   pg_table_size(c.oid),
                   pg_indexes_size(c.oid),
                   GREATEST(c.reltuples, 0)::bigint
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND c.relname LIKE 'okx%'
            ORDER BY c.relname
            """;

    private final JdbcTemplate jdbc;

    public StorageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StorageReport report() {
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        Long databaseBytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);

        Set<String> hypertables = new LinkedHashSet<>();
        Map<String, long[]> chunks = new LinkedHashMap<>();
        Map<String, long[]> sizes = new LinkedHashMap<>();
        Map<String, long[]> compression = new LinkedHashMap<>();
        Map<String, String[]> settings = new LinkedHashMap<>();
        Map<String, Long> compressAfter = new LinkedHashMap<>();
        Map<String, Long> approximateRows = new LinkedHashMap<>();
        List<StorageReport.CompressionPolicy> policies = new ArrayList<>();
        String version = timescaleVersion();

        if (version != null) {
            try {
                jdbc.query("SELECT hypertable_name FROM timescaledb_information.hypertables "
                        + "WHERE hypertable_schema = 'public' ORDER BY hypertable_name",
                        rs -> {
                            hypertables.add(rs.getString(1));
                        });
                jdbc.query("SELECT hypertable_name, count(*), count(*) FILTER (WHERE is_compressed) "
                        + "FROM timescaledb_information.chunks WHERE hypertable_schema = 'public' "
                        + "GROUP BY 1 ORDER BY 1",
                        rs -> {
                            chunks.put(rs.getString(1), new long[] { rs.getLong(2), rs.getLong(3) });
                        });
                for (String name : hypertables) {
                    Long total = jdbc.queryForObject("SELECT hypertable_size(?::regclass)", Long.class, name);
                    sizes.put(name, new long[] { total == null ? 0L : total });
                    Long approxRows = jdbc.queryForObject("SELECT approximate_row_count(?::regclass)", Long.class,
                            name);
                    if (approxRows != null) {
                        approximateRows.put(name, approxRows);
                    }
                    jdbc.query("SELECT before_compression_total_bytes, after_compression_total_bytes "
                            + "FROM hypertable_compression_stats(?::regclass)", rs -> {
                                compression.put(name, new long[] {
                                        nullableLong(rs.getObject(1)), nullableLong(rs.getObject(2)) });
                            }, name);
                    jdbc.query("SELECT segmentby, orderby FROM timescaledb_information.hypertable_compression_settings "
                            + "WHERE hypertable = ?::regclass", rs -> {
                                settings.put(name, new String[] { rs.getString(1), rs.getString(2) });
                            }, name);
                }
                jdbc.query("SELECT job_id, proc_name, hypertable_name, schedule_interval::text, config::text "
                        + "FROM timescaledb_information.jobs WHERE proc_name LIKE 'policy%compression%' "
                        + "ORDER BY hypertable_name, job_id", rs -> {
                            String hypertableName = rs.getString(3);
                            policies.add(new StorageReport.CompressionPolicy(rs.getLong(1), rs.getString(2),
                                    hypertableName, rs.getString(4), rs.getString(5)));
                            Long after = compressAfterOf(rs.getString(5));
                            if (hypertableName != null && after != null) {
                                compressAfter.putIfAbsent(hypertableName, after);
                            }
                        });
            } catch (DataAccessException ex) {
                log.warn("timescale metadata unavailable, falling back to plain table sizes: {}", ex.getMessage());
                hypertables.clear();
            }
        }

        List<StorageReport.TableInfo> tables = jdbc.query(TABLES_SQL, (rs, rowNum) -> {
            String name = rs.getString(1);
            long tableBytes = rs.getLong(2);
            long indexBytes = rs.getLong(3);
            long rows = Math.max(0L, rs.getLong(4));
            boolean isHypertable = hypertables.contains(name);
            long totalBytes = isHypertable && sizes.containsKey(name) ? sizes.get(name)[0] : tableBytes + indexBytes;
            if (isHypertable) {
                rows = approximateRows.getOrDefault(name, 0L);
            } else if (rows == 0) {
                // 从未 ANALYZE 过的小表 reltuples 是 0，这里直接数一次（表都很小）
                Long exact = jdbc.queryForObject("SELECT count(*) FROM " + quoteIdent(name), Long.class);
                rows = exact == null ? 0L : exact;
            }
            Double bytesPerRow = rows > 0 ? round2((double) totalBytes / rows) : null;
            return new StorageReport.TableInfo(name, isHypertable, tableBytes, indexBytes, totalBytes, rows,
                    bytesPerRow);
        });
        tables.sort(Comparator.comparingLong(StorageReport.TableInfo::totalBytes).reversed());

        List<StorageReport.HypertableInfo> hypertableInfos = new ArrayList<>();
        for (String name : hypertables) {
            long[] chunk = chunks.getOrDefault(name, new long[] { 0L, 0L });
            long[] compressionBytes = compression.get(name);
            Long before = compressionBytes == null ? null : compressionBytes[0];
            Long after = compressionBytes == null ? null : compressionBytes[1];
            Double ratio = before != null && after != null && after > 0
                    ? round2((double) before / after)
                    : null;
            String[] setting = settings.getOrDefault(name, new String[] { null, null });
            hypertableInfos.add(new StorageReport.HypertableInfo(name,
                    sizes.getOrDefault(name, new long[] { 0L })[0], chunk[0], chunk[1], before, after, ratio,
                    setting[0], setting[1], compressAfter.get(name)));
        }

        StorageReport.TimescaleInfo timescale = new StorageReport.TimescaleInfo(
                version != null, version, hypertableInfos, policies);
        return new StorageReport(database, databaseBytes == null ? 0L : databaseBytes, timescale, tables);
    }

    /** 表名来自 pg_class 且已按 okx* 过滤，仍用双引号包裹避免大小写/特殊字符问题。 */
    private static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private String timescaleVersion() {
        List<String> versions = jdbc.query("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'",
                (rs, rowNum) -> rs.getString(1));
        return versions.isEmpty() ? null : versions.get(0);
    }

    private static final Pattern COMPRESS_AFTER = Pattern.compile("\"compress_after\"\\s*:\\s*(\\d+)");

    /**
     * 从 policy 配置里取 compress_after（bigint 时间列的毫秒整数）。
     * interval 形式（如 "7 days"）不是毫秒，返回 null 让调用方按"未知"展示。
     */
    static Long compressAfterOf(String configJson) {
        if (configJson == null) {
            return null;
        }
        Matcher matcher = COMPRESS_AFTER.matcher(configJson);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
