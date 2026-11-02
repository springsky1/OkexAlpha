-- 恢复 okx_kline 的压缩（批量装载历史数据后执行）
--
-- 顺序很重要：必须先设回压缩参数（此时不能有压缩 chunk，pause 时已全部解压），
-- 再打开策略调度，最后手动触发一次压缩把历史 chunk 立刻压起来（否则要等第二天）。

\echo '== resume compression on public.okx_kline =='

ALTER TABLE public.okx_kline SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'bar,kline_type',
    timescaledb.compress_orderby = 'inst_id, ts_ms DESC'
);

SELECT alter_job(job_id, scheduled => true)
  FROM timescaledb_information.jobs
 WHERE proc_name = 'policy_compression'
   AND hypertable_name = 'okx_kline';

-- CALL 里不能写子查询，用 DO 块先取 job_id 再触发
DO $$
DECLARE
    jid int;
BEGIN
    SELECT job_id INTO jid
      FROM timescaledb_information.jobs
     WHERE proc_name = 'policy_compression'
       AND hypertable_name = 'okx_kline';
    IF jid IS NOT NULL THEN
        CALL run_job(jid);
    END IF;
END $$;

VACUUM (ANALYZE) public.okx_kline;

SELECT pg_size_pretty(hypertable_size('public.okx_kline')) AS kline_after,
       (SELECT count(*) FILTER (WHERE is_compressed) || '/' || count(*)
          FROM timescaledb_information.chunks
         WHERE hypertable_name = 'okx_kline') AS compressed_chunks;
