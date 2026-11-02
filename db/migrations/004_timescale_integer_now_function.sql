-- 004: 给 bigint 时间维度的 hypertable 绑定整型 now 函数。
--
-- 为什么必须有：
--   okx_kline.ts_ms / okx_funding_rate.funding_time_ms 是 bigint（毫秒），TimescaleDB 的
--   策略作业（压缩、保留、连续聚合）需要知道"现在"才能算 lag。bigint 维度没有原生 now()，
--   必须用 set_integer_now_func() 绑定一个返回 bigint 的 STABLE 函数；否则压缩策略每天执行时报
--   "integer_now function not set"，数据永远不会被压缩。
--
-- 幂等：函数用 CREATE OR REPLACE；set_integer_now_func 对同一 hypertable 重复调用是安全的。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb') THEN
        EXECUTE $fn$
            CREATE OR REPLACE FUNCTION okx_now_ms() RETURNS bigint
            LANGUAGE sql STABLE PARALLEL SAFE AS $body$
                SELECT (EXTRACT(EPOCH FROM now()) * 1000)::bigint
            $body$;
        $fn$;

        IF EXISTS (SELECT 1 FROM timescaledb_information.hypertables
                    WHERE hypertable_schema = 'public' AND hypertable_name = 'okx_kline') THEN
            PERFORM set_integer_now_func('public.okx_kline', 'okx_now_ms', replace_if_exists => true);
        END IF;

        IF EXISTS (SELECT 1 FROM timescaledb_information.hypertables
                    WHERE hypertable_schema = 'public' AND hypertable_name = 'okx_funding_rate') THEN
            PERFORM set_integer_now_func('public.okx_funding_rate', 'okx_now_ms', replace_if_exists => true);
        END IF;
    END IF;
END
$$;
