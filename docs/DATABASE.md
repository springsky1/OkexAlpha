# 数据库结构（PostgreSQL 18）

库名 `okex_alpha`，表定义在 [`db/schema.sql`](../db/schema.sql)，全部 `CREATE TABLE IF NOT EXISTS`，可重复执行。
应用启动时 `okx.db.auto-init=true` 会再次执行 classpath 内的同一份 schema 并校验 12 张表存在。

## 全局约定

| 约定 | 说明 |
| --- | --- |
| 时间列 | 统一 **epoch 毫秒 `bigint`**，命名后缀 `_ms`（`ts_ms` / `funding_time_ms` / `list_time_ms` / `exp_time_ms` / `next_funding_time_ms` / `cursor_ms` / `last_ts_ms`） |
| 元数据时间 | `created_at` / `started_at` / `finished_at` / `updated_at` / `last_seen_at` / `requested_at` 用 `timestamptz`（运维可读，不参与业务主键） |
| 数值列 | 统一 `numeric`（对应 Java `BigDecimal`，不丢精度；OKX 以字符串下发数值） |
| 幂等写入 | 行情类表全部 `INSERT ... ON CONFLICT ... DO UPDATE`，重复执行不产生重复行 |
| 表前缀 | 全部 `okx_`，便于 `\dt okx_*` 一眼看全 |
| 主键 | 业务表用自然键（品种 / K 线四元组 / 合约+资金时间等），只有作业、任务、日志用 `bigserial` |

12 张表分四组：

| 组 | 表 |
| --- | --- |
| 基础清单 | `okx_instrument`、`okx_coin` |
| 行情与统计 | `okx_kline`、`okx_funding_rate`、`okx_long_short_ratio`、`okx_open_interest_volume`、`okx_taker_volume`、`okx_open_interest` |
| 编排 | `okx_backfill_job`、`okx_backfill_task`、`okx_sync_cursor` |
| 审计 | `okx_api_call_log` |

---

## 1. okx_instrument —— 交易品种

上游 `/api/v5/public/instruments` 全量落库，`raw` 保留原始 JSON。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `inst_id` | text | 否（PK） | 品种 ID，如 `BTC-USDT-SWAP` |
| `inst_type` | text | 否 | `SPOT`/`MARGIN`/`SWAP`/`FUTURES`/`OPTION` |
| `inst_family` | text | 是 | 家族，如 `BTC-USDT`；拉 OPTION 时必需 |
| `uly` | text | 是 | 标的指数 |
| `base_ccy` | text | 是 | 基础币 |
| `quote_ccy` | text | 是 | 计价币 |
| `settle_ccy` | text | 是 | 结算币 |
| `ct_type` | text | 是 | 合约类型（`linear`/`inverse`） |
| `ct_val` | numeric | 是 | 合约面值 |
| `ct_val_ccy` | text | 是 | 面值币种 |
| `ct_mult` | numeric | 是 | 合约乘数 |
| `lever` | numeric | 是 | 最大杠杆 |
| `tick_sz` | numeric | 是 | 价格最小变动 |
| `lot_sz` | numeric | 是 | 数量最小变动 |
| `min_sz` | numeric | 是 | 最小下单量 |
| `list_time_ms` | bigint | 是 | 上线时间（epoch ms） |
| `exp_time_ms` | bigint | 是 | 交割/到期时间 |
| `state` | text | 是 | 状态（`live`/`suspend`/`expired`…） |
| `rule_type` | text | 是 | 交易规则类型 |
| `category` | text | 是 | 上游 `category` 原值 |
| `inst_category` | text | 是 | 上游 `instCategory` 原值 |
| `option_type` | text | 是 | 上游 `optType`（`C`/`P`） |
| `alias` | text | 是 | 别名 |
| `raw` | jsonb | 是 | 上游完整 JSON |
| `last_seen_at` | timestamptz | 否 | 最近一次在同步结果中出现的时间（默认 `now()`） |
| `updated_at` | timestamptz | 否 | 行更新时间（默认 `now()`） |

主键：`inst_id`。
索引：`idx_okx_instrument_type(inst_type)`、`idx_okx_instrument_family(inst_family)`、`idx_okx_instrument_base_ccy(base_ccy)`、`idx_okx_instrument_state(state)`。
无外键（派生表 `okx_coin` 不挂 FK，保证整表重建的速度）。

---

## 2. okx_coin —— 平台币种（派生表）

由 `okx_instrument` 的 `base_ccy` / `quote_ccy` / `settle_ccy` 三列聚合而来，每次品种同步后**整体重建**。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `coin` | text | 否（PK） | 币种符号 |
| `channels` | text[] | 是 | 该币出现过的 `inst_type` 去重集合 |
| `roles` | text[] | 是 | 角色集合：`BASE`/`QUOTE`/`SETTLE` |
| `quote_ccys` | text[] | 是 | 关联过的计价币（已剔除 NULL） |
| `inst_count` | integer | 否 | 关联品种总数（`count(DISTINCT inst_id)`） |
| `spot_inst_count` | integer | 否 | SPOT 品种数 |
| `swap_inst_count` | integer | 否 | SWAP 品种数 |
| `futures_inst_count` | integer | 否 | FUTURES 品种数 |
| `option_inst_count` | integer | 否 | OPTION 品种数 |
| `margin_inst_count` | integer | 否 | MARGIN 品种数 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`coin`。索引：`idx_okx_coin_channels`，`USING gin (channels)`（支撑 `? = ANY(channels)` 过滤）。

> 写入策略是 `DELETE FROM okx_coin` + 整表重算，**不是 upsert**：币种的渠道/角色/计价币是多对多聚合，
> 增量维护容易留幽灵行。代价是重建瞬间该表为空，并发读者可能读到 0 行。

---

## 3. okx_kline —— K 线

两类 K 线（CANDLE/MARK）× 24 个 bar 周期共用一张表，用 `kline_type` + `bar` 区分。
只存永续合约（`inst_type='SWAP'`）的 K 线；指数 K 线（INDEX）已下线，`kline_type` 允许值仅 `CANDLE` / `MARK`。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `kline_type` | text | 否（PK 1/4） | `CANDLE` / `MARK` |
| `inst_id` | text | 否（PK 2/4） | 品种 ID |
| `bar` | text | 否（PK 3/4） | 周期 code，如 `1H`、`1Dutc` |
| `ts_ms` | bigint | 否（PK 4/4） | K 线开盘时间（epoch ms） |
| `inst_type` | text | 是 | 冗余的品种类型，便于按类型过滤 |
| `open` | numeric | 是 | 开盘价 |
| `high` | numeric | 是 | 最高价 |
| `low` | numeric | 是 | 最低价 |
| `close` | numeric | 是 | 收盘价 |
| `vol` | numeric | 是 | 成交量（MARK 为 NULL） |
| `vol_ccy` | numeric | 是 | 成交额（币）（MARK 为 NULL） |
| `vol_ccy_quote` | numeric | 是 | 成交额（计价币）（MARK 为 NULL） |
| `confirm` | smallint | 是 | `0`=未收盘（仍在生成），`1`=已收盘 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_kline (kline_type, inst_id, bar, ts_ms)`。
约束：`ck_okx_kline_type CHECK (kline_type IN ('CANDLE','MARK'))`。
索引：`idx_okx_kline_lookup (inst_id, bar, kline_type, ts_ms DESC)`（点查主路径）、`idx_okx_kline_ts_brin USING brin (ts_ms)`（大范围时间扫描）。

> 未建 TimescaleDB hypertable；如需时间分区或压缩需自行改造。

---

## 4. okx_funding_rate —— 资金费率

主键包含资金时间，同一 `instId` 每次结算一行；当前值（`/public/funding-rate`）与历史（`-history`）共用同一结构。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `inst_id` | text | 否（PK 1/2） | 品种 ID |
| `funding_time_ms` | bigint | 否（PK 2/2） | 资金费时间（epoch ms，8 小时一次） |
| `inst_type` | text | 是 | 品种类型 |
| `funding_rate` | numeric | 否 | 本期资金费率 |
| `realized_rate` | numeric | 是 | 实际实现费率（有值即视为已结算） |
| `method` | text | 是 | 上游 `method` |
| `formula_type` | text | 是 | 上游 `formulaType` |
| `settled` | boolean | 否 | 是否已结算（默认 `false`） |
| `premium` | numeric | 是 | 溢价 |
| `interest_rate` | numeric | 是 | 利率 |
| `next_funding_time_ms` | bigint | 是 | 下期资金费时间 |
| `impact_value` | numeric | 是 | 冲击保证金 |
| `min_funding_rate` | numeric | 是 | 费率下限 |
| `max_funding_rate` | numeric | 是 | 费率上限 |
| `ts_ms` | bigint | 是 | 上游采集时间戳 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_funding_rate (inst_id, funding_time_ms)`。索引：`idx_okx_funding_rate_time (inst_id, funding_time_ms DESC)`。

---

## 5. okx_long_short_ratio —— 多空比与精英持仓

四类比值共用一张表，由 `ratio_type` 区分（取值受 CHECK 约束）。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `ratio_type` | text | 否（PK 1/4） | `LSR_ACCOUNT_CCY` / `LSR_ACCOUNT_CONTRACT` / `TOP_TRADER_ACCOUNT` / `TOP_TRADER_POSITION` |
| `scope` | text | 否（PK 2/4） | 币种或 `instId`（语义由 `scope_kind` 决定） |
| `period` | text | 否（PK 3/4） | `5m` / `1H` / `1D` |
| `ts_ms` | bigint | 否（PK 4/4） | 统计时间点（epoch ms） |
| `scope_kind` | text | 否 | `CCY`（按币） / `INST`（按合约） |
| `long_short_ratio` | numeric | 否 | 多空比 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_long_short_ratio (ratio_type, scope, period, ts_ms)`。
约束：`ck_okx_lsr_scope_kind CHECK (scope_kind IN ('CCY','INST'))`、`ck_okx_lsr_ratio_type CHECK (ratio_type IN ('LSR_ACCOUNT_CCY','LSR_ACCOUNT_CONTRACT','TOP_TRADER_ACCOUNT','TOP_TRADER_POSITION'))`。
索引：`idx_okx_lsr_lookup (scope, period, ratio_type, ts_ms DESC)`。

---

## 6. okx_open_interest_volume —— 合约持仓量与成交量

上游 rubik 原始列 `[ts, oi, vol]`，按**币种**维度。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `ccy` | text | 否（PK 1/3） | 币种 |
| `period` | text | 否（PK 2/3） | `5m` / `1H` / `1D` |
| `ts_ms` | bigint | 否（PK 3/3） | 时间点（epoch ms） |
| `oi` | numeric | 是 | 持仓量 |
| `vol` | numeric | 是 | 成交量 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_open_interest_volume (ccy, period, ts_ms)`。无额外索引（主键前缀已覆盖查询）。

---

## 7. okx_taker_volume —— 主动买入/卖出量

上游 rubik 原始列 `[ts, sellVol, buyVol]`；同时支持按币（`CCY`）与按合约（`INST`）两个维度。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `scope_kind` | text | 否（PK 1/5） | `CCY` / `INST` |
| `scope` | text | 否（PK 2/5） | 币种或 `instId` |
| `market_type` | text | 否（PK 3/5） | `CCY` 口径固定 `CONTRACTS`；`INST` 口径按标的 `instType` 三档取值：`CONTRACTS`（衍生品）/ `SPOT`（非衍生品）/ `''`（`instType` 未知）。默认值 `''` |
| `period` | text | 否（PK 4/5） | `5m` / `1H` / `1D` |
| `ts_ms` | bigint | 否（PK 5/5） | 时间点（epoch ms） |
| `sell_vol` | numeric | 是 | 主动卖出量 |
| `buy_vol` | numeric | 是 | 主动买入量 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_taker_volume (scope_kind, scope, market_type, period, ts_ms)`。
约束：`ck_okx_taker_volume_scope_kind CHECK (scope_kind IN ('CCY','INST'))`。
索引：`idx_okx_taker_volume_lookup (scope, period, scope_kind, ts_ms DESC)`。

`market_type` 的取值规则（该列既 `NOT NULL` 又是主键第 3 列，所以必须有值）：

| 口径 | `market_type` | 来源 |
| --- | --- | --- |
| `CCY` | 恒为 `CONTRACTS` | `BackfillPlanner` 规划时固定写入；上游 `/rubik/stat/taker-volume` 只有 `SPOT`/`CONTRACTS` 两个 `instType` |
| `INST` | 衍生品（`SWAP`/`FUTURES`/`OPTION`）→ `CONTRACTS` | 该 `instId` 在 `okx_instrument` 里的 `inst_type` 明确是衍生品 |
| `INST` | 非衍生品（`SPOT`/`MARGIN` 等）→ `SPOT` | 同上，`inst_type` 明确存在且不是衍生品 |
| `INST` | `inst_type` 未知（`instId` 未入库、查不到）→ `''` | **不猜市场**：查不到就落空串表示未知，不按 `SPOT` 兜底 |

`INST` 的三档由 `BackfillPlanner.takerMarketType(instType)` 决定：`instType == null` 直接返回空串，否则按 `InstType.fromCode(instType).isDerivative()` 返回 `CONTRACTS` 或 `SPOT`；取值同时作为 `TaskParams.marketType` 落到 `okx_backfill_task.params`。

写入侧还有一层兜底：`StatisticsRepository.upsertTakerVolume` 对 `marketType` 做 `null → ""` 归一（私有方法 `nullToEmpty`），避免 `NOT NULL` 违约；正常规划路径已保证非 `null`。

---

## 8. okx_open_interest —— 持仓量快照

上游 `/public/open-interest` **只返回当前值**，因此这张表是随时间累积的快照，而不是可回填的历史序列。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `inst_id` | text | 否（PK 1/2） | 品种 ID |
| `ts_ms` | bigint | 否（PK 2/2） | 上游快照时间戳 |
| `inst_type` | text | 是 | 品种类型 |
| `oi` | numeric | 是 | 持仓张数/币数 |
| `oi_ccy` | numeric | 是 | 持仓量（币） |
| `oi_usd` | numeric | 是 | 持仓量（USD） |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`pk_okx_open_interest (inst_id, ts_ms)`。无额外索引。
---

## 9. okx_backfill_job —— 补拉作业

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | bigserial | 否（PK） | 作业 ID |
| `data_type` | text | 否 | `KLINE` / `FUNDING_RATE` / `LONG_SHORT_RATIO` / `OPEN_INTEREST_VOLUME` / `TAKER_VOLUME` / `OPEN_INTEREST` |
| `request` | jsonb | 是 | 提交时的原始请求体 |
| `status` | text | 否 | `PENDING`/`RUNNING`/`PAUSED`/`DONE`/`FAILED`/`CANCELED`（默认 `PENDING`） |
| `task_total` | integer | 否 | 任务总数（刷新计数时重算） |
| `task_done` | integer | 否 | 已完成任务数 |
| `task_failed` | integer | 否 | 失败任务数 |
| `rows_written` | bigint | 否 | 累计写入行数（子任务 `rows_written` 之和） |
| `error` | text | 是 | 作业级错误。**取值即真相**：`setJobStatus` 每次都把传入的 `error` 写进该列，作业转 `DONE` 时传 `null` 会**清空**历史错误，所以 `DONE` 作业的 `error` 恒为 `NULL`。实际可能带值的只有两类：`FAILED`（`<n> task(s) failed` 或崩溃摘要）与重启中断瞬间的 `PAUSED`（`interrupted by restart`，`auto-resume` 接手后立即被清空）。重启中断标记 `interrupted by restart` 由启动恢复写入，`auto-resume` 正是按 `status='PAUSED' AND error='interrupted by restart'` 识别 |
| `created_at` | timestamptz | 否 | 创建时间 |
| `started_at` | timestamptz | 是 | 首次进入 `RUNNING` 的时间（`coalesce` 保留首次值） |
| `finished_at` | timestamptz | 是 | 进入 `DONE`/`FAILED`/`CANCELED` 的时间 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`id`。约束：`ck_okx_job_status CHECK (status IN ('PENDING','RUNNING','PAUSED','DONE','FAILED','CANCELED'))`。
索引：`idx_okx_job_status (status, created_at DESC)`。

---

## 10. okx_backfill_task —— 补拉子任务

一个作业拆成多个可独立续跑的任务；`params` 是 `TaskParams` 的 JSON。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | bigserial | 否（PK） | 任务 ID |
| `job_id` | bigint | 否（FK） | 引用 `okx_backfill_job(id)`，`ON DELETE CASCADE` |
| `task_key` | text | 否（UQ） | 任务标识，如 `KLINE:CANDLE:BTC-USDT-SWAP:1H` |
| `params` | jsonb | 是 | `TaskParams`：`dataType/instId/instType/bar/klineType/ratioType/scope/scopeKind/ccy/period/marketType/startMs/endMs/pageLimit`，未使用的字段为 null |
| `status` | text | 否 | `PENDING`/`RUNNING`/`DONE`/`FAILED`/`SKIPPED`（默认 `PENDING`） |
| `cursor_ms` | bigint | 是 | 续跑游标（epoch ms）。K 线/资金费率按 `after` 向前翻页（初始值即 `endMs`）；rubik 统计按窗口起点向新推进（`page_count = 0` 时从 `startMs` 起算，之后取 `max(cursor_ms, startMs)`） |
| `page_count` | integer | 否 | 已完成页数 |
| `rows_written` | bigint | 否 | 本任务累计写入行数 |
| `attempts` | integer | 否 | 执行尝试次数（每次领取 +1，上限 3） |
| `truncated` | boolean | 否 | 是否因上游保留期被截断（默认 `false`） |
| `last_error` | text | 是 | 最近一次失败原因（截断到 500 字符） |
| `started_at` | timestamptz | 是 | 首次领取时间 |
| `finished_at` | timestamptz | 是 | 结束时间 |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`id`。唯一约束：`uq_okx_task_key UNIQUE (job_id, task_key)`。
外键：`okx_backfill_task.job_id → okx_backfill_job.id ON DELETE CASCADE`（库中唯一的外键）。
约束：`ck_okx_task_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','SKIPPED'))`。
索引：`idx_okx_task_job_status (job_id, status)`。

---

## 11. okx_sync_cursor —— 增量同步水位

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `cursor_key` | text | 否（PK） | 水位键 |
| `last_ts_ms` | bigint | 是 | 最后已同步时间（epoch ms） |
| `updated_at` | timestamptz | 否 | 行更新时间 |

主键：`cursor_key`。写入语句为 `INSERT ... ON CONFLICT (cursor_key) DO UPDATE`。

> **当前没有任何业务代码调用 `SyncCursorRepository.set()`**，`GET /api/v1/sync/status` 的 `cursors` 通常是空对象。该表是预留的增量水位方案。

---

## 12. okx_api_call_log —— 上游调用审计

`OkxApiClient` 每次 HTTP 调用后写一行（`okx.api-log.enabled=false` 时不写）。

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `id` | bigserial | 否（PK） | 自增 ID |
| `endpoint` | text | 否 | 上游路径，如 `/api/v5/market/history-candles` |
| `inst_id` | text | 是 | 关联品种（按币的 rubik 调用写币种） |
| `http_status` | integer | 是 | HTTP 状态码；连接层失败为 `0` 或 null |
| `okx_code` | text | 是 | 上游业务码（成功为 `0`） |
| `okx_msg` | text | 是 | 上游消息（截断到 500 字符） |
| `row_count` | integer | 是 | 本次返回的 `data` 元素个数 |
| `elapsed_ms` | bigint | 是 | 单次调用耗时 |
| `requested_at` | timestamptz | 否 | 请求时间 |

主键：`id`。索引：`idx_okx_api_call_log_time (requested_at DESC)`。
---

## 写入策略

| 表 | 语句形态 | 冲突键 | 说明 |
| --- | --- | --- | --- |
| `okx_instrument` | `INSERT ... ON CONFLICT DO UPDATE` | `inst_id` | 每 500 行一批；同时刷新 `last_seen_at` 与 `updated_at` |
| `okx_coin` | `DELETE` + `INSERT ... SELECT` | — | 整体重建，不做增量 upsert |
| `okx_kline` | `INSERT ... ON CONFLICT DO UPDATE` | `(kline_type, inst_id, bar, ts_ms)` | `confirm` 也在覆盖列表里，所以 `0 → 1` 的收盘更新会生效 |
| `okx_funding_rate` | `INSERT ... ON CONFLICT DO UPDATE` | `(inst_id, funding_time_ms)` | 先落快照（`realized_rate` 可能为 null），后续补写会覆盖 |
| `okx_long_short_ratio` | `INSERT ... ON CONFLICT DO UPDATE` | `(ratio_type, scope, period, ts_ms)` | — |
| `okx_open_interest_volume` | `INSERT ... ON CONFLICT DO UPDATE` | `(ccy, period, ts_ms)` | — |
| `okx_taker_volume` | `INSERT ... ON CONFLICT DO UPDATE` | `(scope_kind, scope, market_type, period, ts_ms)` | `market_type` 参与唯一键，写入前 `null` 归一成 `''`（`StatisticsRepository.nullToEmpty`） |
| `okx_open_interest` | `INSERT ... ON CONFLICT DO UPDATE` | `(inst_id, ts_ms)` | 快照表，同 `ts` 重复采集覆盖 |
| `okx_backfill_job` | `INSERT` + 定向 `UPDATE` | — | 状态机更新；`claimJob` 只在 `PENDING → RUNNING` 时影响 1 行 |
| `okx_backfill_task` | `INSERT ... ON CONFLICT (job_id, task_key) DO NOTHING` | `(job_id, task_key)` | 之后靠 `UPDATE` 推进状态、游标与计数 |
| `okx_sync_cursor` | `INSERT ... ON CONFLICT DO UPDATE` | `cursor_key` | 目前无调用方 |
| `okx_api_call_log` | `INSERT` | — | 只追加，不更新 |

事务边界：补拉每写一页时，**数据 upsert 与 `okx_backfill_task` 的游标/计数更新在同一个事务内提交**
（`BackfillPageWriter.write` 标注 `@Transactional`），因此崩溃后从最后提交的游标继续，不会出现“游标前进了但数据没落库”。

`updated_at` 维护：所有行情表 upsert 都在 `DO UPDATE` 分支显式写 `updated_at = now()`；
作业/任务表由每条 `UPDATE` 语句自带 `updated_at = now()`。**库里没有触发器**，时间由应用维护。
---

## 典型查询

### 按品种类型统计品种数

```sql
SELECT inst_type, count(*) AS instruments, count(DISTINCT inst_family) AS families
  FROM okx_instrument
 GROUP BY inst_type
 ORDER BY instruments DESC;
```

### 各表行数

```sql
SELECT 'okx_instrument' AS tbl, count(*) AS rows FROM okx_instrument
UNION ALL SELECT 'okx_coin', count(*) FROM okx_coin
UNION ALL SELECT 'okx_kline', count(*) FROM okx_kline
UNION ALL SELECT 'okx_funding_rate', count(*) FROM okx_funding_rate
UNION ALL SELECT 'okx_long_short_ratio', count(*) FROM okx_long_short_ratio
UNION ALL SELECT 'okx_open_interest_volume', count(*) FROM okx_open_interest_volume
UNION ALL SELECT 'okx_taker_volume', count(*) FROM okx_taker_volume
UNION ALL SELECT 'okx_open_interest', count(*) FROM okx_open_interest
ORDER BY tbl;
```

```sql
SELECT kline_type, bar, count(*) AS rows, min(ts_ms) AS min_ts, max(ts_ms) AS max_ts
  FROM okx_kline
 GROUP BY kline_type, bar
 ORDER BY kline_type, bar;
```

### K 线缺口检测

用 `lag(ts_ms)` 求相邻两根的间隔，再和该 bar 的标准间隔比对；`missing_bars > 0` 即为断档。

```sql
WITH iv (bar, step_ms) AS (
  VALUES ('1m', 60000::bigint), ('3m', 180000), ('5m', 300000), ('15m', 900000),
         ('30m', 1800000), ('1H', 3600000), ('2H', 7200000), ('4H', 14400000),
         ('6H', 21600000), ('12H', 43200000), ('1D', 86400000), ('2D', 172800000),
         ('3D', 259200000), ('1W', 604800000), ('1M', 2592000000), ('3M', 7776000000),
         ('6Hutc', 21600000), ('12Hutc', 43200000), ('1Dutc', 86400000), ('2Dutc', 172800000),
         ('3Dutc', 259200000), ('1Wutc', 604800000), ('1Mutc', 2592000000), ('3Mutc', 7776000000)
)
SELECT k.inst_id, k.kline_type, k.bar, k.ts_ms AS gap_after_ts,
       k.ts_ms - lag(k.ts_ms) OVER w AS delta_ms,
       iv.step_ms,
       (k.ts_ms - lag(k.ts_ms) OVER w) / iv.step_ms - 1 AS missing_bars
  FROM okx_kline k
  JOIN iv ON iv.bar = k.bar
 WHERE k.inst_id = 'BTC-USDT-SWAP'
   AND k.kline_type = 'CANDLE'
   AND k.bar = '1H'
WINDOW w AS (PARTITION BY k.inst_id, k.kline_type, k.bar ORDER BY k.ts_ms)
ORDER BY k.ts_ms DESC
LIMIT 50;
```

只保留确有缺口的行（窗口函数不能直接写在 `WHERE` 里，需要再包一层）：

```sql
SELECT * FROM (
  SELECT inst_id, kline_type, bar, ts_ms,
         ts_ms - lag(ts_ms) OVER (
             PARTITION BY inst_id, kline_type, bar ORDER BY ts_ms) AS delta_ms
    FROM okx_kline
   WHERE kline_type = 'CANDLE' AND bar = '1H'
) x
WHERE delta_ms > 3600000
ORDER BY ts_ms DESC;
```

### 找最早/最晚已落库时间（判断还需补多久）

```sql
SELECT inst_id, bar, kline_type, min(ts_ms) AS earliest, max(ts_ms) AS latest, count(*) AS rows
  FROM okx_kline
 WHERE inst_id = 'BTC-USDT-SWAP'
 GROUP BY inst_id, bar, kline_type
 ORDER BY bar, kline_type;
```
### 未收盘的 K 线

```sql
SELECT inst_id, bar, count(*) AS open_bars, max(ts_ms) AS latest_ts
  FROM okx_kline
 WHERE confirm = 0
 GROUP BY inst_id, bar
 ORDER BY open_bars DESC
 LIMIT 20;
```

### 某品种最近的资金费率

```sql
SELECT funding_time_ms, funding_rate, realized_rate, settled
  FROM okx_funding_rate
 WHERE inst_id = 'BTC-USDT-SWAP'
 ORDER BY funding_time_ms DESC
 LIMIT 21;
```

### 多空比与精英持仓按时间对齐

```sql
SELECT coalesce(a.ts_ms, b.ts_ms) AS ts_ms,
       a.long_short_ratio AS top_trader_account,
       b.long_short_ratio AS top_trader_position
  FROM okx_long_short_ratio a
  FULL JOIN okx_long_short_ratio b
    ON b.ts_ms = a.ts_ms
   AND b.period = a.period
   AND b.scope = a.scope
   AND b.ratio_type = 'TOP_TRADER_POSITION'
 WHERE a.ratio_type = 'TOP_TRADER_ACCOUNT'
   AND a.scope = 'BTC-USDT-SWAP'
   AND a.period = '1H'
 ORDER BY ts_ms DESC
 LIMIT 24;
```

### 主动买卖量与持仓量并列

```sql
SELECT t.ts_ms, t.buy_vol, t.sell_vol, t.buy_vol - t.sell_vol AS net_taker,
       o.oi, o.vol AS market_volume
  FROM okx_taker_volume t
  LEFT JOIN okx_open_interest_volume o
    ON o.ccy = 'BTC' AND o.period = t.period AND o.ts_ms = t.ts_ms
 WHERE t.scope_kind = 'INST'
   AND t.scope = 'BTC-USDT-SWAP'
   AND t.period = '1H'
 ORDER BY t.ts_ms DESC
 LIMIT 48;
```

### 作业进度

```sql
SELECT id, data_type, status, task_done, task_total, task_failed, rows_written, error
  FROM okx_backfill_job
 ORDER BY id DESC
 LIMIT 20;
```

```sql
SELECT job_id, task_key, status, attempts, cursor_ms, page_count, rows_written, truncated, last_error
  FROM okx_backfill_task
 WHERE status IN ('FAILED', 'RUNNING')
 ORDER BY job_id DESC, id
 LIMIT 50;
```

### 上游调用的错误分布（最近一天）

```sql
SELECT endpoint, okx_code, count(*) AS calls,
       round(avg(elapsed_ms)) AS avg_ms, max(elapsed_ms) AS max_ms
  FROM okx_api_call_log
 WHERE requested_at > now() - interval '1 day'
   AND coalesce(okx_code, '') <> '0'
 GROUP BY endpoint, okx_code
 ORDER BY calls DESC;
```

---

## 体量参考

- 单品种单周期 `1H` 一年约 8.7K 行；主键 `(kline_type, inst_id, bar, ts_ms)` 上的写入全部走 `ON CONFLICT`，重复补拉同一区间只产生 `UPDATE`，不会撑大表。
- `okx_api_call_log` 只追加，是唯一随运行时间单调增长的表，可按 `requested_at` 定期清理（例如保留 7 天）。
- `okx_coin` 每次品种同步都会整表重算，行数等于全平台币种数（约 400+）。
