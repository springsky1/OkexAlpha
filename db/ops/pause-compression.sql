-- 暂停 okx_kline 的压缩（批量装载历史数据前用）
--
-- 为什么需要：压缩窗口（30 天）以外的 chunk 处于压缩态时，任何 INSERT / ON CONFLICT DO UPDATE
-- 都会让 TimescaleDB 把整个 chunk 透明解压、写入、再重压。回补一整年 1m 数据时，
-- 每个 chunk 有几十万到几百万行，逐页写入会慢到不可用。
-- 做法：先停掉压缩策略（否则策略每天执行会报 "compression not enabled"），
-- 再把已压缩 chunk 全部解压，最后关掉压缩开关。
-- 装载完成后执行 db/ops/resume-compression.sql 恢复。

\echo '== pause compression on public.okx_kline =='

SELECT alter_job(job_id, scheduled => false)
  FROM timescaledb_information.jobs
 WHERE proc_name = 'policy_compression'
   AND hypertable_name = 'okx_kline';

DO $$
DECLARE
    c regclass;
    n int := 0;
BEGIN
    FOR c IN
        SELECT ch.chunk
          FROM show_chunks('public.okx_kline') AS ch(chunk)
          JOIN timescaledb_information.chunks ci
            ON format('%I.%I', ci.chunk_schema, ci.chunk_name)::regclass = ch.chunk
         WHERE ci.is_compressed
    LOOP
        PERFORM decompress_chunk(c);
        n := n + 1;
    END LOOP;
    RAISE NOTICE 'decompressed % chunk(s)', n;
END $$;

ALTER TABLE public.okx_kline SET (timescaledb.compress = false);

SELECT 'compression paused' AS status;
