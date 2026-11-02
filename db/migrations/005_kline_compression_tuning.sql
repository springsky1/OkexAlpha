-- 005: okx_kline 压缩参数调优（segmentby/orderby）
--
-- 背景：003 用 segmentby = 'inst_id,bar,kline_type' + orderby = 'ts_ms DESC'。在 7 天/chunk 的粒度下，
-- 每个 chunk 会被拆成非常多极窄的 segment（462 个 instId × 多个 bar × 2 种 kline_type），
-- 单个 segment 里往往只有几百行甚至几十行，压缩元数据（每 segment 一套 + 列 header）的开销
-- 超过列式编码省下来的空间，实测出现 "poor compression ratio" 警告（压缩后反而变大）。
--
-- 实测（真实行情数据 347,609 行，okx_tune 库 A/B/C 对照，全部先把 chunk 解压再用新参数重压）：
--   A 现设置 segmentby='inst_id,bar,kline_type', orderby='ts_ms DESC'        → hypertable 42 MB
--   B 本迁移 segmentby='bar,kline_type',        orderby='inst_id, ts_ms DESC' → 20 MB（5.6x）
--   C 不分段     segmentby='',                 orderby='inst_id, ts_ms DESC' → 20 MB
-- 查询延迟（B 与 A 对比，ms，取稳定值）：
--   尾查 300 根 1m 单合约   A 6~7        B 6~7
--   单合约全量 count         A 7.9        B 6.6~8.2
--   单合约 1D 全量 count     A 6.9        B 5.9~7.5
--   全表 group by bar        A 13.9~14.8  B 6.0   ← B 明显更快（不必跨 462 个 segment 逐个解压）
--   C 在「单合约 1D 全量」上 63~66ms（没有 segment 剪枝，每次都要解压整个 chunk），故不选 C。
--
-- 因此选 B：体积与 C 相同，但保留 bar/kline_type 的 segment 剪枝，读性能最好。
-- 修改压缩设置前必须先把已压缩的 chunk 全部解压（TimescaleDB 的限制）。
-- 重压交给 006（本迁移只改设置，不批量压缩，避免迁移耗时随数据量无上限增长）。
--
-- 坑（已在真实库踩到）：bigint 时间维度的 hypertable，timescaledb_information.chunks 的
-- range_start / range_end 恒为 NULL，想按时间范围筛 chunk 必须用
-- show_chunks('表', older_than => okx_now_ms() - 毫秒数)（见 006）。

DO $$
DECLARE
    c regclass;
    decompressed int := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        RAISE NOTICE '005: timescaledb not installed, skip compression tuning';
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.hypertables
         WHERE hypertable_schema = 'public' AND hypertable_name = 'okx_kline'
    ) THEN
        RAISE NOTICE '005: public.okx_kline is not a hypertable, skip compression tuning';
        RETURN;
    END IF;

    -- 解压：TimescaleDB 不允许在有压缩 chunk 时修改压缩设置
    FOR c IN
        SELECT format('%I.%I', chunk_schema, chunk_name)::regclass
          FROM timescaledb_information.chunks
         WHERE hypertable_schema = 'public'
           AND hypertable_name = 'okx_kline'
           AND is_compressed
    LOOP
        PERFORM decompress_chunk(c);
        decompressed := decompressed + 1;
    END LOOP;

    -- 新设置：按 bar + kline_type 分段（每个 chunk 只有十来个 segment），
    -- orderby 用 inst_id, ts_ms DESC，让同一合约的点在 segment 内连续排列，利于增量编码。
    EXECUTE 'ALTER TABLE public.okx_kline SET ('
         || 'timescaledb.compress = true, '
         || 'timescaledb.compress_segmentby = ''bar,kline_type'', '
         || 'timescaledb.compress_orderby = ''inst_id, ts_ms DESC'')';

    RAISE NOTICE '005: okx_kline compression settings tuned (segmentby=bar,kline_type orderby=inst_id,ts_ms DESC, decompressed=%)',
        decompressed;
END $$;
