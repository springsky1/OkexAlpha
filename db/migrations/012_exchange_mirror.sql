-- 012_exchange_mirror：订单中心「平台真实订单」本地镜像
--
-- 订单中心的当前委托 / 历史委托 / 历史仓位 / 交易账单改成读本地库（而不是每次进页面现打 OKX），
-- 同时支持勾选多行做营收汇总。三张表都是交易所数据的本地镜像：
--   okx_exchange_order     委托：pending + orders-history + orders-history-archive
--   okx_exchange_position  已平仓仓位：positions-history
--   okx_exchange_fill      逐笔成交：fills-history
--
-- 同步一律 UPSERT：委托按 ordId、成交按 tradeId、仓位按「合约 + 保证金模式 + 方向 + 开仓时间 + 平仓时间」
-- 去重，后一次同步只更新状态与盈亏，不会把更早的历史覆盖掉。
-- 委托表额外维护「当前挂单快照」语义：每次同步挂单后，把 state 仍是 live/partially_filled 但已不在
-- 本次挂单列表里的行删掉，避免早已成交/撤单的委托一直显示成挂单；它们的终态会由同一次同步的历史
-- 委托 UPSERT 回来（OKX 历史委托覆盖最近 7 天，归档接口覆盖最近 3 个月）。
--
-- 全新库由 db/schema.sql 直接建出；升级库靠本迁移补齐（幂等）。

CREATE TABLE IF NOT EXISTS okx_exchange_order (
    ord_id        text PRIMARY KEY,
    cl_ord_id     text,
    inst_id       text NOT NULL,
    inst_type     text,
    td_mode       text,
    side          text,
    pos_side      text,
    ord_type      text,
    sz            double precision,
    px            double precision,
    avg_px        double precision,
    fill_sz       double precision,
    state         text,
    lever         double precision,
    fee           double precision,
    fee_ccy       text,
    pnl           double precision,
    reduce_only   boolean,
    algo_id       text,
    cancel_source text,
    c_time_ms     bigint,
    u_time_ms     bigint,
    archived      boolean NOT NULL DEFAULT false,
    synced_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_order_time ON okx_exchange_order (c_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_order_inst ON okx_exchange_order (inst_id, c_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_order_state ON okx_exchange_order (state, c_time_ms DESC);

CREATE TABLE IF NOT EXISTS okx_exchange_position (
    id            bigserial PRIMARY KEY,
    inst_id       text NOT NULL,
    inst_type     text,
    mgn_mode      text NOT NULL DEFAULT '',
    pos_side      text NOT NULL DEFAULT '',
    type          text,
    lever         double precision,
    open_avg_px   double precision,
    close_avg_px  double precision,
    mark_px       double precision,
    pnl           double precision,
    pnl_ratio     double precision,
    realized_pnl  double precision,
    fee           double precision,
    funding_fee   double precision,
    liq_px        double precision,
    c_time_ms     bigint NOT NULL DEFAULT 0,
    u_time_ms     bigint NOT NULL DEFAULT 0,
    synced_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_okx_exchange_position UNIQUE (inst_id, mgn_mode, pos_side, c_time_ms, u_time_ms)
);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_position_time ON okx_exchange_position (c_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_position_inst ON okx_exchange_position (inst_id, c_time_ms DESC);

CREATE TABLE IF NOT EXISTS okx_exchange_fill (
    trade_id   text PRIMARY KEY,
    ord_id     text,
    inst_id    text NOT NULL,
    inst_type  text,
    side       text,
    pos_side   text,
    fill_sz    double precision,
    fill_px    double precision,
    fill_time_ms bigint,
    fee        double precision,
    fee_ccy    text,
    exec_type  text,
    tag        text,
    pnl        double precision,
    synced_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_fill_time ON okx_exchange_fill (fill_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_fill_inst ON okx_exchange_fill (inst_id, fill_time_ms DESC);
CREATE INDEX IF NOT EXISTS idx_okx_exchange_fill_order ON okx_exchange_fill (ord_id);

COMMENT ON TABLE okx_exchange_order IS
    'OKX 委托订单本地镜像（pending + orders-history + orders-history-archive），按 ordId UPSERT';
COMMENT ON TABLE okx_exchange_position IS
    'OKX 已平仓仓位本地镜像（positions-history），按合约+模式+方向+开平时间 UPSERT';
COMMENT ON TABLE okx_exchange_fill IS
    'OKX 逐笔成交本地镜像（fills-history），按 tradeId UPSERT';
