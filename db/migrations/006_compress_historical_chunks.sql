-- 006: 按新压缩设置（005）把历史 chunk 立刻压起来
--
-- 为什么单独一个迁移：005 需要先把压缩 chunk 全部解压才能改设置，改完若不重压，
-- 库会停在"全解压"状态，直到下一次 policy_compression 每日执行才收敛。
-- 这里立刻把超出压缩窗口（30 天，与 003 的 add_compression_policy 参数一致）的 chunk 压掉。
--
-- 关键写法：bigint 时间维度的 hypertable，timescaledb_information.chunks 的
-- range_start / range_end 恒为 NULL（无法和 timestamptz 比较），因此必须用
-- show_chunks('表', older_than => 毫秒时间戳) 来筛选历史 chunk，
-- 且这里的 older_than 是"绝对时间值"而不是"偏移量"，要自己减掉窗口长度。
--
-- 幂等：已压缩的 chunk 会被跳过；新库（没有历史数据）零个 chunk，几毫秒返回。

DO $$
DECLARE
    c regclass;
    cutoff bigint;
    compressed int := 0;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        RAISE NOTICE '006: timescaledb not installed, skip chunk compression';
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.hypertables
         WHERE hypertable_schema = 'public' AND hypertable_name = 'okx_kline'
    ) THEN
        RAISE NOTICE '006: public.okx_kline is not a hypertable, skip chunk compression';
        RETURN;
    END IF;

    -- 与 policy_compression 的 compress_after 保持一致：30 天
    cutoff := okx_now_ms() - 2592000000;

    FOR c IN
        SELECT ch.chunk
          FROM show_chunks('public.okx_kline', older_than => cutoff) AS ch(chunk)
          LEFT JOIN timescaledb_information.chunks ci
            ON format('%I.%I', ci.chunk_schema, ci.chunk_name)::regclass = ch.chunk
         WHERE ci.is_compressed IS NOT TRUE
    LOOP
        PERFORM compress_chunk(c);
        compressed := compressed + 1;
    END LOOP;

    RAISE NOTICE '006: compressed % historical okx_kline chunk(s) with the tuned settings', compressed;
END $$;
