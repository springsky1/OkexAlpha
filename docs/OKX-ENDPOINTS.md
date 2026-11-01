# 上游 OKX 端点（本服务实际消费的部分）

全部为**公有 GET 接口**，不需要 API Key、不需要签名，只需 `User-Agent` 与 `Accept: application/json`。
统一响应外壳：

```json
{ "code": "0", "msg": "", "data": [ ... ] }
```

`code != "0"` 表示业务失败（HTTP 状态码通常仍是 200）。本服务只取 `data` 节点，不做业务解释，解析逻辑在 `OkxApiClient` 与 `client/model/*Parser`。

## 代码常量与本文件的对应

路径常量集中在 `backend/src/main/java/com/okexalpha/client/OkxApiClient.java`：

| 常量 | 值 |
| --- | --- |
| `PATH_INSTRUMENTS` | `/api/v5/public/instruments` |
| `PATH_FUNDING_RATE` | `/api/v5/public/funding-rate` |
| `PATH_FUNDING_RATE_HISTORY` | `/api/v5/public/funding-rate-history` |
| `PATH_OPEN_INTEREST` | `/api/v5/public/open-interest` |
| `PATH_OI_VOLUME` | `/api/v5/rubik/stat/contracts/open-interest-volume` |
| `PATH_TAKER_VOLUME` | `/api/v5/rubik/stat/taker-volume` |
| `PATH_TAKER_VOLUME_CONTRACT` | `/api/v5/rubik/stat/taker-volume-contract` |

K 线路径不在 `OkxApiClient` 里，而是挂在 `KlineType` 枚举上（`backend/src/main/java/com/okexalpha/domain/KlineType.java`）：

| 枚举 | `path()` | `hasVolume()` |
| --- | --- | --- |
| `CANDLE` | `/api/v5/market/history-candles` | true（9 列） |
| `MARK` | `/api/v5/market/history-mark-price-candles` | false（6 列） |

多空比路径挂在 `RatioType` 枚举上（`backend/src/main/java/com/okexalpha/domain/RatioType.java`）：

| 枚举 | `path()` | `scopeKind()` |
| --- | --- | --- |
| `LSR_ACCOUNT_CCY` | `/api/v5/rubik/stat/contracts/long-short-account-ratio` | `CCY` |
| `LSR_ACCOUNT_CONTRACT` | `/api/v5/rubik/stat/contracts/long-short-account-ratio-contract` | `INST` |
| `TOP_TRADER_ACCOUNT` | `/api/v5/rubik/stat/contracts/long-short-account-ratio-contract-top-trader` | `INST` |
| `TOP_TRADER_POSITION` | `/api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader` | `INST` |

## 端点清单

| # | 方法与路径 | 客户端方法 | 消费方 |
| --- | --- | --- | --- |
| 1 | `GET /api/v5/public/instruments` | `getInstruments(instType, instFamily)` | `InstrumentSyncService` |
| 2 | `GET /api/v5/market/history-candles` | `getCandles(KlineType, instId, bar, after, before, limit)` | `KlineTaskHandler` |
| 3 | `GET /api/v5/market/history-mark-price-candles` | 同上 | `KlineTaskHandler` |
| 4 | `GET /api/v5/public/funding-rate` | `getFundingRate(instId)` | 已实现但当前补拉链路只用历史端点 |
| 5 | `GET /api/v5/public/funding-rate-history` | `getFundingRateHistory(instId, after, before, limit)` | `FundingRateTaskHandler` |
| 6 | `GET /api/v5/rubik/stat/contracts/long-short-account-ratio` | `getLongShortRatio(RatioType, scope, Period, beginMs, endMs)` | `StatisticsTaskHandler` |
| 7 | `GET /api/v5/rubik/stat/contracts/long-short-account-ratio-contract` | 同上 | `StatisticsTaskHandler` |
| 8 | `GET /api/v5/rubik/stat/contracts/long-short-account-ratio-contract-top-trader` | 同上 | `StatisticsTaskHandler` |
| 9 | `GET /api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader` | 同上 | `StatisticsTaskHandler` |
| 10 | `GET /api/v5/rubik/stat/contracts/open-interest-volume` | `getOpenInterestVolume(ccy, Period, beginMs, endMs)` | `StatisticsTaskHandler` |
| 11 | `GET /api/v5/rubik/stat/taker-volume` | `getTakerVolumeByCcy(ccy, instType, Period, beginMs, endMs)` | `StatisticsTaskHandler` |
| 12 | `GET /api/v5/rubik/stat/taker-volume-contract` | `getTakerVolumeByInst(instId, Period, beginMs, endMs)` | `StatisticsTaskHandler` |
| 13 | `GET /api/v5/public/open-interest` | `getOpenInterest(instType, instId)` | `OpenInterestTaskHandler` |

注意：**没有**使用 `/api/v5/market/candles`（当前K线），只走 `history-*` 系列，因为补拉需要 `after` 游标向前翻页。

---

## 1. 交易品种 instruments

```text
GET /api/v5/public/instruments?instType=SPOT|MARGIN|SWAP|FUTURES|OPTION[&instFamily=BTC-USD]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `instType` | 是 | 5 类之一 |
| `instFamily` | OPTION 必填 | 家族（如 `BTC-USD`），来自 SWAP/FUTURES 的 `instFamily` 去重 |

返回 `data` 是对象数组，关键字段：`instId`、`instType`、`instFamily`、`uly`、`baseCcy`、`quoteCcy`、`settleCcy`、`ctType`、`ctVal`、`ctValCcy`、`ctMult`、`lever`、`tickSz`、`lotSz`、`minSz`、`listTime`、`expTime`、`state`、`ruleType`、`category`、`instCategory`、`optType`、`alias`。
数值字段以**字符串**下发，落库前统一转 `BigDecimal`；缺字段或空串按 null 处理（`CandleParser.decimal`、`RubikParser.decimal` 都是这个约定）。

实测数量级（公有接口全量）：

| `instType` | 条数 |
| --- | --- |
| `SPOT` | ≈1211 |
| `SWAP` | ≈532 |
| `FUTURES` | ≈285（仅币本位） |
| `MARGIN` | ≈266 |
| `OPTION` | 按家族，`BTC-USD` ≈722 |

---

## 2–3. K 线（history-candles / history-mark-price-candles）

只做永续合约（`instType=SWAP`）的两类 K 线；指数 K 线端点（`history-index-candles`）已从代码移除。

```text
GET /api/v5/market/history-candles?instId=BTC-USDT-SWAP&bar=1H&after=1738368000000&limit=300
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `instId` | 是 | 品种 ID |
| `bar` | 是 | 24 个 code 之一（`1m`…`3M`，含 `6Hutc`/`1Mutc` 等 utc 变体） |
| `after` | 否 | 取**早于**该 ts 的记录（补拉游标） |
| `before` | 否 | 取**新于**该 ts 的记录 |
| `limit` | 否 | 单次条数，实际上限 **300**（服务端 `MAX_API_LIMIT=300` 强制夹紧） |

返回 `data` 是**数组的数组**，不是对象：

| 类型 | 列数 | 列顺序 |
| --- | --- | --- |
| `CANDLE` | 9 | `[ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]` |
| `MARK` | 6 | `[ts, o, h, l, c, confirm]` |

`confirm=0` 表示该根仍在生成（最新一根几乎总是 0），`confirm=1` 表示已收盘。落库时 `confirm` 也在 `ON CONFLICT DO UPDATE` 的覆盖列中，所以 0→1 会被原地更新。

实测回溯深度（可能因品种而异）：

| bar | 可回溯 |
| --- | --- |
| `1m` | ≥ 1 年 |
| `1H` | ≥ 2 年 |
| `1D` | ≥ 5 年 |

翻页策略（`KlineTaskHandler`）：从 `endMs` 开始，用 `after=cursor` 取一页，把 `cursor` 设为该页最小 `ts`（若最小 `ts` 未前进则 `cursor-1` 防止死循环），直到 `cursor <= startMs` 或返回空页。
---

## 4–5. 资金费率

```text
GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP
GET /api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP&after=1738368000000&limit=100
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `instId` | 是 | 品种 ID |
| `after` / `before` | 否 | 同 K 线语义，按 `fundingTime` 向前/向后翻页 |
| `limit` | 否 | 单次条数，最多 **300**；本服务固定按 **100** 一页取（`FundingRateTaskHandler.PAGE = 100`） |

返回 `data` 是对象数组，关键字段：`instType`、`instId`、`fundingRate`、`realizedRate`、`fundingTime`、`nextFundingTime`、`premium`、`interestRate`、`method`、`formulaType`、`settState`/`settledState`、`impactValue`、`minFundingRate`、`maxFundingRate`、`ts`。

**保留期：约 96 天**（每 8 小时一次结算）。翻到空页即判定到达边界：`FundingRateTaskHandler` 会把 task 标记 `truncated=true` 后正常结束，不算失败。

`/public/funding-rate`（当前值）在 `OkxApiClient.getFundingRate` 里已实现，但当前补拉链路只调用历史端点；当前快照会随历史页一起覆盖进来。

---

## 6–9. 多空比与精英持仓（rubik）

```text
GET /api/v5/rubik/stat/contracts/long-short-account-ratio?ccy=BTC&period=1H&begin=...&end=...
GET /api/v5/rubik/stat/contracts/long-short-account-ratio-contract?instId=BTC-USDT-SWAP&period=1H&begin=..&end=..
GET /api/v5/rubik/stat/contracts/long-short-account-ratio-contract-top-trader?instId=..&period=..&begin=..&end=..
GET /api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader?instId=..&period=..&begin=..&end=..
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `ccy` | `CCY` 维度必填 | 币种（仅 `long-short-account-ratio`） |
| `instId` | `INST` 维度必填 | 品种 ID（其余 3 个端点） |
| `period` | 是 | **只支持 `5m` / `1H` / `1D`**，其它值上游返回 `51000` |
| `begin` / `end` | 否 | epoch 毫秒区间 |

返回 `data` 是二元数组的数组：`[ts, ratio]`。

保留期与单次窗口（`Period` 枚举里写死，规划与执行都以此为准）：

| `period` | 上游保留期 | 单次请求窗口 | 服务端常量 |
| --- | --- | --- | --- |
| `5m` | ≈ **48 小时** | 8 小时 | `Period.M5.retentionMs=48h` / `windowMs=8h` |
| `1H` | ≈ **30 天** | 4 天 | `Period.H1.retentionMs=30d` / `windowMs=4d` |
| `1D` | ≈ **180 天** | 90 天 | `Period.D1.retentionMs=180d` / `windowMs=90d` |

`begin` 越过保留边界时上游返回 **`50030`**，本服务视为“该窗口无数据”，跳过窗口继续下一个（`StatisticsTaskHandler.handleRangeError`），任务不失败。
执行时 `startMs` 会先被夹到 `now - retentionMs`，若整个区间都在保留期之外，直接返回 `truncated=true` 的空结果。

---

## 10. 合约持仓量与成交量（rubik open-interest-volume）

```text
GET /api/v5/rubik/stat/contracts/open-interest-volume?ccy=BTC&period=1H&begin=...&end=...
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `ccy` | 是 | 币种 |
| `period` | 是 | `5m` / `1H` / `1D` |
| `begin` / `end` | 否 | epoch 毫秒 |

返回 `[ts, oi, vol]` 三元数组，分别落 `okx_open_interest_volume.oi` / `.vol`。保留期同上表。

---

## 11–12. 主动买卖量（rubik taker-volume）

```text
GET /api/v5/rubik/stat/taker-volume?ccy=BTC&instType=SPOT|CONTRACTS&period=1H&begin=..&end=..
GET /api/v5/rubik/stat/taker-volume-contract?instId=BTC-USDT-SWAP&period=1H&begin=..&end=..
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `ccy` | 按币时必填 | 币种 |
| `instType` | 按币时必填 | **只接受 `SPOT` 与 `CONTRACTS`**；传 `SWAP` 会返回 `51000` |
| `instId` | 按合约时必填 | 品种 ID |
| `period` | 是 | `5m` / `1H` / `1D` |
| `begin` / `end` | 否 | epoch 毫秒 |

返回 `[ts, sellVol, buyVol]`。本服务按币采集时固定传 `instType=CONTRACTS`（`TaskParams.marketType="CONTRACTS"`），落库时 `scope_kind='CCY'`；按合约采集走 12 号端点、`scope_kind='INST'`，`market_type` 由该 `instId` 的 `inst_type` 决定：衍生品（`SWAP`/`FUTURES`/`OPTION`）写 `CONTRACTS`，`SPOT`/`MARGIN` 等非衍生品写 `SPOT`，`inst_type` 未知（`instId` 未入库）写空串表示不猜市场；落库前再由 `StatisticsRepository.nullToEmpty` 兜底（`BackfillPlanner.takerMarketType`）。

---

## 13. 持仓量快照（public/open-interest）

```text
GET /api/v5/public/open-interest?instType=SWAP&instId=BTC-USDT-SWAP
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `instType` | 否 | `SWAP`/`FUTURES`/`OPTION` 等；与 `instId` 都不传则返回全量 |
| `instId` | 否 | 品种 ID |

返回 `data` 是**对象数组**：`{instId, instType, oi, oiCcy, oiUsd, ts}`。**只给当前值，没有历史区间**，所以本服务的补拉就是“跑一次落一行”，历史靠重复采集累积。

---

## 限流、重试与错误分类

### 客户端限流（滑动窗口，按端点组）

`OkxApiClient.groupOf(path)` 按路径分组，`OkxRateLimiter` 用 2000 ms 滑动窗口阻塞等待配额：

| 组 | 判定 | 默认配额（每 2 秒） | 配置键 |
| --- | --- | --- | --- |
| `rubik` | 路径以 `/api/v5/rubik/` 开头 | 4 | `okx.rate-limit.rubik-per-2s` |
| `candles` | 路径含 `/market/history` | 16 | `okx.rate-limit.candles-per-2s` |
| `funding` | 路径含 `/funding-rate` | 10 | `okx.rate-limit.funding-per-2s` |
| `instruments` | 其余（含 `public/open-interest`） | 16 | `okx.rate-limit.instruments-per-2s` |

`okx.rate-limit.enabled=false` 可整体关闭。

默认值是实测标定结果（同一台机器、同一出口 IP、单实例）：

| 组 | 官方上限 | 实测干净吞吐 | 超限现象 |
| --- | --- | --- | --- |
| `candles` | 20 次/2s | 16 次/2s → 6.3 页/秒，0 错误 | 20 次/2s → HTTP 429，总耗时反而变长 |
| `funding` | 10 次/2s | 4.9 次/秒，0 错误 | — |
| `rubik` | 5 次/2s | 未压满（配额仍是最紧的一组） | — |

单个 task 内部按页串行翻页，因此**单合约**补拉的速度上限≈1/单次延迟（实测约 5 页/秒）；
多合约并发的总吞吐才由 `okx.backfill.worker-threads` 与每组每 2 秒配额共同决定。

### 重试

`fetchData` 里最多尝试 `okx.retry.max-attempts` 次（默认 3），退避为 `base * 2^(attempt-1) + jitter(0..250ms)`，上限 10 秒（`base = max(50, okx.retry.base-backoff-ms)`）。

| 情形 | 分类 | 是否重试 | 最终映射到本服务 HTTP |
| --- | --- | --- | --- |
| HTTP `429` | `RATE_LIMITED` | 是 | 429 `RATE_LIMITED` |
| HTTP `>= 500` | `UPSTREAM_ERROR` | 是 | 502 `UPSTREAM_ERROR` |
| 响应体无法解析为 JSON | `UPSTREAM_ERROR` | 是 | 502 `UPSTREAM_ERROR` |
| IO / 连接异常 | `NETWORK` | 是 | 502 `UPSTREAM_ERROR` |
| `code` ∈ {`50011`,`50013`,`50061`} | `RATE_LIMITED` | 是 | 429 `RATE_LIMITED` |
| `code` = `50030` | `RANGE_EXHAUSTED` | **否** | 422 `INVALID_PARAM`（补拉内部跳过该窗口，不冒泡） |
| `code` ∈ {`51000`,`51001`,`51002`,`51010`,`51014`} | `INVALID_PARAM` | **否** | 422 `INVALID_PARAM` |
| 其它非 `0` 的 `code` | `UPSTREAM_ERROR` | 是 | 502 `UPSTREAM_ERROR` |

补充：task 级还有一层重试。`BackfillRunner.MAX_ATTEMPTS = 3`，task 失败会被 `releaseTask` 放回 `PENDING`，
连续 3 次失败才置 `FAILED` 并把作业标 `FAILED`（可 `resume` 后重跑）。

### 错误码速查

| 上游码 | 含义 | 本服务处理 |
| --- | --- | --- |
| `50011` | 触发限流 | 指数退避重试 |
| `51000` | 参数错误（如 `period` 不支持、`instType` 传 `SWAP` 给 taker-volume） | 直接失败，落 task `last_error` |
| `51001` | `instId` 不存在 | 直接失败 |
| `50030` | `begin`/`end` 超出可回溯范围 | 视为到达保留边界，跳过 |

---

## 保留期汇总（实测）

| 数据 | 上游保留 | 越界表现 | 服务端处理 |
| --- | --- | --- | --- |
| `funding-rate-history` | ≈ 96 天 | 返回空 `data` | task 标 `truncated=true` 后正常结束 |
| rubik `5m` | ≈ 48 小时 | `50030` 或空 `data` | 规划阶段夹到保留边界；执行时越界窗口跳过 |
| rubik `1H` | ≈ 30 天 | 同上 | 同上 |
| rubik `1D` | ≈ 180 天 | 同上 | 同上 |
| `public/open-interest` | 仅当前值 | 无区间概念 | 每次采集落一行快照 |
| K 线 `1m` / `1H` / `1D` | ≥1 年 / ≥2 年 / ≥5 年 | 返回空 `data` | 空页即结束，task 正常完成 |

---

## 调用审计

每次上游调用（含重试的每一次）都会写一行 `okx_api_call_log`：`endpoint`、`inst_id`、`http_status`、`okx_code`、`okx_msg`（截断 500 字符）、`row_count`、`elapsed_ms`。
`okx.api-log.enabled=false` 可关闭；写日志失败只打 debug 日志，不影响主流程。
