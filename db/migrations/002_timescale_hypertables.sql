-- =====================================================================
-- 002: TimescaleDB hypertable（按时间自动分块）
--   okx_kline        : ts_ms 为时间维，7 天一个 chunk
--   okx_funding_rate : funding_time_ms 为时间维，7 天一个 chunk
-- 说明：
--   * bigint 时间列的 chunk_time_interval 必须是同单位整数（毫秒），不能用 INTERVAL
--     （否则报 "invalid interval type for bigint dimension"）。
--   * migrate_data => true：把已有普通表数据原地搬进 chunk。
--   * 转换成功后删除 BRIN(ts_ms)：分块 + 压缩后 BRIN 不再有收益，只会占索引空间。
-- 幂等：if_not_exists + DROP INDEX IF EXISTS。
-- 降级：服务器没装 timescaledb 时只发 NOTICE，保留普通表（读写路径完全不受影响）。
-- =====================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS timescaledb';
        RAISE NOTICE 'migration 002: timescaledb extension ready';
    ELSE
        RAISE NOTICE 'migration 002: timescaledb not available, keeping plain tables';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        PERFORM create_hypertable('okx_kline', 'ts_ms',
            chunk_time_interval => 604800000::bigint, migrate_data => true, if_not_exists => true);
        PERFORM create_hypertable('okx_funding_rate', 'funding_time_ms',
            chunk_time_interval => 604800000::bigint, migrate_data => true, if_not_exists => true);
        DROP INDEX IF EXISTS idx_okx_kline_ts_brin;
        RAISE NOTICE 'migration 002: hypertables ready (7-day chunks)';
    END IF;
END $$;
