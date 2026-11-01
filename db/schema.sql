-- =====================================================================
-- OkexAlpha 数据库结构（PostgreSQL 18）
-- 幂等：全部 IF NOT EXISTS，可重复执行
-- 约定：时间列统一 epoch 毫秒 bigint（*_ms）
-- 数值：K线/资金费率等长时序表用 double precision（8 字节，压缩率高），
--       品种/币种等小表仍用 numeric（无损，行数少无所谓）
-- 时序增强（TimescaleDB）由 db/migrations/*.sql 在启动时增量应用，
-- 见 docs/STORAGE.md；本文件只负责"建出干净的基础表"。
-- =====================================================================

-- 1. 交易品种（含 SPOT/SWAP/FUTURES/OPTION/MARGIN）
CREATE TABLE IF NOT EXISTS okx_instrument (
    inst_id       text PRIMARY KEY,
    inst_type     text NOT NULL,
    inst_family   text,
    uly           text,
    base_ccy      text,
    quote_ccy     text,
    settle_ccy    text,
    ct_type       text,
    ct_val        numeric,
    ct_val_ccy    text,
    ct_mult       numeric,
    lever         numeric,
    tick_sz       numeric,
    lot_sz        numeric,
    min_sz        numeric,
    list_time_ms  bigint,
    exp_time_ms   bigint,
    state         text,
    rule_type     text,
    category      text,
    inst_category text,
    option_type   text,
    alias         text,
    raw           jsonb,
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_okx_instrument_type        ON okx_instrument (inst_type);
CREATE INDEX IF NOT EXISTS idx_okx_instrument_family      ON okx_instrument (inst_family);
CREATE INDEX IF NOT EXISTS idx_okx_instrument_base_ccy    ON okx_instrument (base_ccy);
CREATE INDEX IF NOT EXISTS idx_okx_instrument_state       ON okx_instrument (state);

-- 2. 平台支持的币种（由 okx_instrument 派生）
CREATE TABLE IF NOT EXISTS okx_coin (
    coin                  text PRIMARY KEY,
    channels              text[],
    roles                 text[],
    quote_ccys            text[],
    inst_count            integer NOT NULL DEFAULT 0,
    spot_inst_count       integer NOT NULL DEFAULT 0,
    swap_inst_count       integer NOT NULL DEFAULT 0,
    futures_inst_count    integer NOT NULL DEFAULT 0,
    option_inst_count     integer NOT NULL DEFAULT 0,
    margin_inst_count     integer NOT NULL DEFAULT 0,
    updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_okx_coin_channels ON okx_coin USING gin (channels);

-- 3. K 线：kline_type = CANDLE | MARK（只补拉永续合约 SWAP，指数 K 线已下线）
-- double precision：价格列为 float8，比 numeric 每列省 varlena 头，且能走列式压缩
-- confirm boolean：true=已收敛（OKX 1），false=当前未收盘 bar
CREATE TABLE IF NOT EXISTS okx_kline (
    kline_type    text NOT NULL,
    inst_id       text NOT NULL,
    bar           text NOT NULL,
    ts_ms         bigint NOT NULL,
    inst_type     text,
    open          double precision,
    high          double precision,
    low           double precision,
    close         double precision,
    vol           double precision,
    vol_ccy       double precision,
    vol_ccy_quote double precision,
    confirm       boolean NOT NULL DEFAULT false,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_kline PRIMARY KEY (kline_type, inst_id, bar, ts_ms),
    CONSTRAINT ck_okx_kline_type CHECK (kline_type IN ('CANDLE', 'MARK'))
);
-- 主键已按 (kline_type, inst_id, bar, ts_ms) 排序，等值前缀 + 时间范围可直接用 PK 索引；
-- 额外 lookup 索引用于"先按 inst_id/bar 过滤再取最近 N 根"的倒序扫描。
CREATE INDEX IF NOT EXISTS idx_okx_kline_lookup ON okx_kline (inst_id, bar, kline_type, ts_ms DESC);

-- 4. 资金费率（当前快照 + 历史）
CREATE TABLE IF NOT EXISTS okx_funding_rate (
    inst_id              text NOT NULL,
    funding_time_ms      bigint NOT NULL,
    inst_type            text,
    funding_rate         double precision NOT NULL,
    realized_rate        double precision,
    method               text,
    formula_type         text,
    settled              boolean NOT NULL DEFAULT false,
    premium              double precision,
    interest_rate        double precision,
    next_funding_time_ms bigint,
    impact_value         double precision,
    min_funding_rate     double precision,
    max_funding_rate     double precision,
    ts_ms                bigint,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_funding_rate PRIMARY KEY (inst_id, funding_time_ms)
);
CREATE INDEX IF NOT EXISTS idx_okx_funding_rate_time ON okx_funding_rate (inst_id, funding_time_ms DESC);

-- 5. 多空比 / 精英持仓（ratio_type 见 domain.RatioType）
CREATE TABLE IF NOT EXISTS okx_long_short_ratio (
    ratio_type       text NOT NULL,
    scope            text NOT NULL,
    period           text NOT NULL,
    ts_ms            bigint NOT NULL,
    scope_kind       text NOT NULL,
    long_short_ratio numeric NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_long_short_ratio PRIMARY KEY (ratio_type, scope, period, ts_ms),
    CONSTRAINT ck_okx_lsr_scope_kind CHECK (scope_kind IN ('CCY', 'INST')),
    CONSTRAINT ck_okx_lsr_ratio_type CHECK (ratio_type IN (
        'LSR_ACCOUNT_CCY', 'LSR_ACCOUNT_CONTRACT', 'TOP_TRADER_ACCOUNT', 'TOP_TRADER_POSITION'))
);
CREATE INDEX IF NOT EXISTS idx_okx_lsr_lookup ON okx_long_short_ratio (scope, period, ratio_type, ts_ms DESC);

-- 6. 合约持仓量与成交量（rubik, 按币）
CREATE TABLE IF NOT EXISTS okx_open_interest_volume (
    ccy        text NOT NULL,
    period     text NOT NULL,
    ts_ms      bigint NOT NULL,
    oi         numeric,
    vol        numeric,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_open_interest_volume PRIMARY KEY (ccy, period, ts_ms)
);

-- 7. 主动买入/卖出量（rubik）
CREATE TABLE IF NOT EXISTS okx_taker_volume (
    scope_kind  text NOT NULL,
    scope       text NOT NULL,
    market_type text NOT NULL DEFAULT '',
    period      text NOT NULL,
    ts_ms       bigint NOT NULL,
    sell_vol    numeric,
    buy_vol     numeric,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_taker_volume PRIMARY KEY (scope_kind, scope, market_type, period, ts_ms),
    CONSTRAINT ck_okx_taker_volume_scope_kind CHECK (scope_kind IN ('CCY', 'INST'))
);
CREATE INDEX IF NOT EXISTS idx_okx_taker_volume_lookup ON okx_taker_volume (scope, period, scope_kind, ts_ms DESC);

-- 8. 持仓量快照（public/open-interest，仅当前值）
CREATE TABLE IF NOT EXISTS okx_open_interest (
    inst_id    text NOT NULL,
    ts_ms      bigint NOT NULL,
    inst_type  text,
    oi         numeric,
    oi_ccy     numeric,
    oi_usd     numeric,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_okx_open_interest PRIMARY KEY (inst_id, ts_ms)
);

-- 9. 补拉作业
CREATE TABLE IF NOT EXISTS okx_backfill_job (
    id           bigserial PRIMARY KEY,
    data_type    text NOT NULL,
    request      jsonb,
    status       text NOT NULL DEFAULT 'PENDING',
    task_total   integer NOT NULL DEFAULT 0,
    task_done    integer NOT NULL DEFAULT 0,
    task_failed  integer NOT NULL DEFAULT 0,
    rows_written bigint NOT NULL DEFAULT 0,
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_okx_job_status CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELED'))
);
CREATE INDEX IF NOT EXISTS idx_okx_job_status ON okx_backfill_job (status, created_at DESC);

-- 10. 补拉子任务（一个 job 拆成多个可独立续跑的 task）
CREATE TABLE IF NOT EXISTS okx_backfill_task (
    id           bigserial PRIMARY KEY,
    job_id       bigint NOT NULL REFERENCES okx_backfill_job (id) ON DELETE CASCADE,
    task_key     text NOT NULL,
    params       jsonb,
    status       text NOT NULL DEFAULT 'PENDING',
    cursor_ms    bigint,
    page_count   integer NOT NULL DEFAULT 0,
    rows_written bigint NOT NULL DEFAULT 0,
    attempts     integer NOT NULL DEFAULT 0,
    truncated    boolean NOT NULL DEFAULT false,
    last_error   text,
    started_at   timestamptz,
    finished_at  timestamptz,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_okx_task_key UNIQUE (job_id, task_key),
    CONSTRAINT ck_okx_task_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);
CREATE INDEX IF NOT EXISTS idx_okx_task_job_status ON okx_backfill_task (job_id, status);

-- 11. 增量同步水位
CREATE TABLE IF NOT EXISTS okx_sync_cursor (
    cursor_key text PRIMARY KEY,
    last_ts_ms bigint,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 12. 上游调用日志（okx.api-log.enabled 可关）
CREATE TABLE IF NOT EXISTS okx_api_call_log (
    id           bigserial PRIMARY KEY,
    endpoint     text NOT NULL,
    inst_id      text,
    http_status  integer,
    okx_code     text,
    okx_msg      text,
    row_count    integer,
    elapsed_ms   bigint,
    requested_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_okx_api_call_log_time ON okx_api_call_log (requested_at DESC);

-- 13. 结构迁移账本（db/migrations/*.sql 的已应用记录，见 config/SchemaMigrationRunner）
CREATE TABLE IF NOT EXISTS okx_schema_migration (
    id          text PRIMARY KEY,
    checksum    text NOT NULL,
    duration_ms bigint,
    applied_at  timestamptz NOT NULL DEFAULT now()
);
