-- 007：回测/策略表补列（P1 回测引擎需要的阶段、请求快照、合约与 OOS 维度）
-- 幂等：全部 ADD COLUMN IF NOT EXISTS；只加列不改既有列语义。

ALTER TABLE okx_strategy ADD COLUMN IF NOT EXISTS mode text NOT NULL DEFAULT 'SHADOW';
ALTER TABLE okx_strategy ADD COLUMN IF NOT EXISTS capital_per_coin_usd double precision;

ALTER TABLE okx_backtest_run ADD COLUMN IF NOT EXISTS phase text;
ALTER TABLE okx_backtest_run ADD COLUMN IF NOT EXISTS request jsonb;
ALTER TABLE okx_backtest_run ADD COLUMN IF NOT EXISTS coin_total integer NOT NULL DEFAULT 0;
ALTER TABLE okx_backtest_run ADD COLUMN IF NOT EXISTS coin_done integer NOT NULL DEFAULT 0;

ALTER TABLE okx_backtest_result ADD COLUMN IF NOT EXISTS inst_id text;
ALTER TABLE okx_backtest_result ADD COLUMN IF NOT EXISTS segment text NOT NULL DEFAULT 'FULL';
ALTER TABLE okx_backtest_result ADD COLUMN IF NOT EXISTS metrics jsonb;
ALTER TABLE okx_backtest_result ADD COLUMN IF NOT EXISTS oos_net_pnl_pct double precision;

ALTER TABLE okx_backtest_trade ADD COLUMN IF NOT EXISTS layers integer NOT NULL DEFAULT 1;
ALTER TABLE okx_backtest_trade ADD COLUMN IF NOT EXISTS avg_entry double precision;
ALTER TABLE okx_backtest_trade ADD COLUMN IF NOT EXISTS max_adverse_pct double precision;

CREATE INDEX IF NOT EXISTS idx_okx_bt_result_inst ON okx_backtest_result (run_id, inst_id, segment);
