-- 011_daily_martingale：I0 口径修复用到的结构变更
--
-- 1) okx_backtest_trade.first_entry_px（F8）
--    原实现把加权均价写进 entry_px，与"首仓成交价"语义混淆：实盘对账、逐笔比对都要求
--    "这笔交易第一笔成交在什么价"，加仓后均价会漂移，不能混用。新增独立列，旧数据不回填
--    （entry_px 保留给旧口径查询），新写入同时写 first_entry_px 与 entry_px=首仓价。
--
-- 2) okx_data_quality_snapshot（方案 §1.2）
--    每日 00:10 UTC 的数据校验结果留档：缺口 C1/C2、合法性 C4、对齐 C5、聚合一致性 C6。
--    保留 history 才能看出"哪一天开始变脏"，只存最新一次会丢掉趋势。
--
-- 全新库由 db/schema.sql 直接建出；升级库靠本迁移补齐（幂等）。

ALTER TABLE okx_backtest_trade
    ADD COLUMN IF NOT EXISTS first_entry_px double precision;

CREATE TABLE IF NOT EXISTS okx_data_quality_snapshot (
    id          bigserial PRIMARY KEY,
    bar         text NOT NULL,
    scope       text NOT NULL,
    inst_id     text,
    checked_at  timestamptz NOT NULL DEFAULT now(),
    window_start_ms bigint,
    window_end_ms   bigint,
    missing_count   integer NOT NULL DEFAULT 0,
    gap_count       integer NOT NULL DEFAULT 0,
    bad_ohlc_count  integer NOT NULL DEFAULT 0,
    misaligned_count integer NOT NULL DEFAULT 0,
    agg_mismatch_count integer NOT NULL DEFAULT 0,
    stale_sec       bigint,
    status      text NOT NULL,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_okx_dq_checked ON okx_data_quality_snapshot (checked_at DESC, bar);
