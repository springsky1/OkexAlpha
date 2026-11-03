-- 010_strategy_status_stopped：okx_strategy.status 允许 STOPPED
--
-- 订单中心的「停止」按钮需要把策略从 CANDIDATE/PROMOTED 改成"已停止"，而原来的
-- ck_okx_strategy_status 只允许 DRAFT/CANDIDATE/PROMOTED/ARCHIVED，写入 STOPPED 会被约束拒绝。
-- ARCHIVED 语义是"归档"，与"用户主动停掉、之后还可能再启用"不同，所以把 STOPPED 补成一等状态。
--
-- 全新库由 db/schema.sql 直接建出带 STOPPED 的约束，本迁移对它重建成等价定义（幂等、无副作用）；
-- 升级库靠本迁移把旧约束换掉。RUNNING_PREDICATE 仍只认 SHADOW + CANDIDATE/PROMOTED，
-- 因此 STOPPED 天然不会被影子引擎调度，不需要改查询。

ALTER TABLE okx_strategy DROP CONSTRAINT IF EXISTS ck_okx_strategy_status;

ALTER TABLE okx_strategy
    ADD CONSTRAINT ck_okx_strategy_status
    CHECK (status IN ('DRAFT', 'CANDIDATE', 'PROMOTED', 'ARCHIVED', 'STOPPED'));
