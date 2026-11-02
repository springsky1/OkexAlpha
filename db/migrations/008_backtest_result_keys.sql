-- 008: 回测结果按「参数组合 + 段位 + 币种」唯一
-- 原约束 uq_okx_bt_result_key (run_id, param_key) 不允许同一组合同时存在
-- 聚合行（segment='ALL'）与单币明细行（segment='COIN'），这里换成更细的唯一索引。
ALTER TABLE okx_backtest_result DROP CONSTRAINT IF EXISTS uq_okx_bt_result_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_okx_bt_result_key
    ON okx_backtest_result (run_id, param_key, segment, COALESCE(inst_id, ''));
