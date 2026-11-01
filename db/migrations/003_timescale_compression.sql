-- =====================================================================
-- 003: 列式压缩 + 过期 chunk 自动压缩 + 表级 autovacuum 调参
--   实测（525,600 行 1 年 1m 数据）：137 MB -> 30 MB（5.8x，60.6 B/行）
--   压缩后数据实际存放在 TOAST，统计大小时必须用 hypertable_size()
--   或 after_compression_total_bytes，看 after_compression_table_bytes 会误判。
--   segmentby 选 inst_id,bar,kline_type：同一合约同周期相邻时间戳几乎同值，
--   列内增量编码压缩率最高。
--   压缩策略 30 天：30 天内的热数据保持行存（可写、可快查），更早的自动列存。
--   注意：对已压缩 chunk 直接 INSERT ... ON CONFLICT 仍可用（Timescale 会透明解压
--   再重压），只是慢；所以补拉 30 天以前区间会明显变慢，但结果正确。
-- 幂等：if_not_exists + SET (幂等)。
-- =====================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        EXECUTE 'ALTER TABLE okx_kline SET (timescaledb.compress, '
             || 'timescaledb.compress_segmentby = ''inst_id,bar,kline_type'', '
             || 'timescaledb.compress_orderby = ''ts_ms DESC'')';
        EXECUTE 'ALTER TABLE okx_funding_rate SET (timescaledb.compress, '
             || 'timescaledb.compress_segmentby = ''inst_id'', '
             || 'timescaledb.compress_orderby = ''funding_time_ms DESC'')';
        -- 30 天 = 2,592,000,000 毫秒（bigint 时间列必须传同单位整数）
        PERFORM add_compression_policy('okx_kline', BIGINT '2592000000', if_not_exists => true);
        PERFORM add_compression_policy('okx_funding_rate', BIGINT '2592000000', if_not_exists => true);

        -- 补拉是"只追加/覆盖少量新行"的形态，默认 20% 死元组阈值会让表长期膨胀
        EXECUTE 'ALTER TABLE okx_kline SET (autovacuum_vacuum_scale_factor = 0.02, '
             || 'autovacuum_vacuum_insert_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01)';
        EXECUTE 'ALTER TABLE okx_funding_rate SET (autovacuum_vacuum_scale_factor = 0.02, '
             || 'autovacuum_vacuum_insert_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.01)';
        RAISE NOTICE 'migration 003: compression policy (30 days) + autovacuum ready';
    ELSE
        RAISE NOTICE 'migration 003: timescaledb not available, compression skipped';
    END IF;
END $$;
