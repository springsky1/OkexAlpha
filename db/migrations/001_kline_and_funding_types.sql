-- =====================================================================
-- 001: 时序列类型收敛（省存储 + 加快读取）
--   okx_kline.open/high/low/close/vol/vol_ccy/vol_ccy_quote : numeric(变长) -> double precision(8 字节)
--   okx_kline.confirm                                      : smallint -> boolean(1 字节)
--   okx_funding_rate 的 7 个比率列                          : numeric -> double precision
-- 幂等：按 information_schema 当前类型判断，已转换过则跳过。
-- 说明：价格/费率保留 double 的 15~17 位有效数字，对行情数据足够；numeric 每列额外带
--       变长头部(varlena)与 dscale，长表上是主要存储与解包开销来源。
-- =====================================================================
DO $$
DECLARE
    converted int := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'okx_kline'
                 AND column_name = 'open' AND data_type = 'numeric') THEN
        ALTER TABLE okx_kline
            ALTER COLUMN open          TYPE double precision USING open::double precision,
            ALTER COLUMN high          TYPE double precision USING high::double precision,
            ALTER COLUMN low           TYPE double precision USING low::double precision,
            ALTER COLUMN close         TYPE double precision USING close::double precision,
            ALTER COLUMN vol           TYPE double precision USING vol::double precision,
            ALTER COLUMN vol_ccy       TYPE double precision USING vol_ccy::double precision,
            ALTER COLUMN vol_ccy_quote TYPE double precision USING vol_ccy_quote::double precision;
        converted := converted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'okx_kline'
                 AND column_name = 'confirm' AND udt_name = 'int2') THEN
        ALTER TABLE okx_kline ALTER COLUMN confirm TYPE boolean USING (confirm <> 0);
        ALTER TABLE okx_kline ALTER COLUMN confirm SET DEFAULT false;
        converted := converted + 1;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'okx_funding_rate'
                 AND column_name = 'funding_rate' AND data_type = 'numeric') THEN
        ALTER TABLE okx_funding_rate
            ALTER COLUMN funding_rate     TYPE double precision USING funding_rate::double precision,
            ALTER COLUMN realized_rate    TYPE double precision USING realized_rate::double precision,
            ALTER COLUMN premium          TYPE double precision USING premium::double precision,
            ALTER COLUMN interest_rate    TYPE double precision USING interest_rate::double precision,
            ALTER COLUMN impact_value     TYPE double precision USING impact_value::double precision,
            ALTER COLUMN min_funding_rate TYPE double precision USING min_funding_rate::double precision,
            ALTER COLUMN max_funding_rate TYPE double precision USING max_funding_rate::double precision;
        converted := converted + 1;
    END IF;

    RAISE NOTICE 'migration 001: % table(s) type-converted', converted;
END $$;
