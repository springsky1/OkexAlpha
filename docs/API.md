# OkexAlpha HTTP API

所有接口前缀 `http://<host>:18080`，默认端口 18080，无鉴权。
数据面接口只读本地 PostgreSQL，不直接打上游；写操作仅限 `/api/v1/sync/*` 与 `/api/v1/backfill/*`。

## 约定

### 统一响应体

成功与失败的响应体结构一致，由 `ApiResponse<T>` 序列化而来：

```json
{ "code": "0", "msg": "", "data": {} }
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | 成功恒为 `"0"`；失败为错误码（`INVALID_PARAM` / `NOT_FOUND` / `CONFLICT` / `RATE_LIMITED` / `UPSTREAM_ERROR` / `INTERNAL_ERROR`） |
| `msg` | string | 成功为空串；失败为可读消息 |
| `data` | any | 成功时为业务数据；失败时为 `null`（字段始终出现） |

> `spring.jackson.default-property-inclusion=non_null`：**`data` 内部对象的 null 字段不会出现在 JSON 中**。
> 例如 MARK K 线的 `vol`、`volCcy`、`volCcyQuote` 三列恒为 null，响应里直接缺失。

### 时间参数：`startMs` / `endMs` 与 `start` / `end`

所有带时间范围的查询都同时支持两套参数（`TimeArgs`）：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `startMs` | long | epoch 毫秒，**优先级最高** |
| `endMs` | long | epoch 毫秒，**优先级最高** |
| `start` | string | ISO-8601，仅在对应的 `startMs` 缺失时生效 |
| `end` | string | ISO-8601，仅在对应的 `endMs` 缺失时生效 |

- ISO 形式必须带时区偏移，例如 `2026-01-01T00:00:00Z`、`2026-01-01T08:00:00+08:00`；裸日期 `2026-01-01` 会解析失败 → `400 INVALID_PARAM`（`invalid ISO-8601 time: ...`）。
- 两套参数混用是允许的：例如 `startMs` + `end` 同时给出，会取 `startMs` 与解析后的 `end`。
- 同时给出且 `start >= end` → `400 INVALID_PARAM`（`startMs must be earlier than endMs`）。
- 解析后的语义是闭区间：`ts_ms >= start` 且 `ts_ms <= end`。

### 分页参数

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `limit` | int | 每页条数，默认值与上限按端点不同；`limit < 1` → `400 INVALID_PARAM`（`limit must be >= 1`）；超过上限会被**静默截断**到上限，不报错 |
| `offset` | int | 偏移，默认 `0`；`offset < 0` → `400 INVALID_PARAM`（`offset must be >= 0`） |

只有 `/api/v1/coins`、`/api/v1/instruments`、`/api/v1/backfill/jobs` 支持 `offset`；
K 线 / 资金费率 / 统计类查询走时间窗口 + `limit`，内部固定 `offset=0`。

### 排序

时间序列类端点使用 `order`：`desc`（默认）或 `asc`。判定条件是 `"asc".equalsIgnoreCase(order)`，
**其它任何取值都按 `desc` 处理**，不会报错。

### HTTP 状态码与错误码

| HTTP | `code` | 触发条件 |
| --- | --- | --- |
| 200 | `0` | 成功 |
| 202 | `0` | 补拉作业已接受（`POST /api/v1/backfill/*`），作业异步执行 |
| 400 | `INVALID_PARAM` | 参数校验失败：`limit`/`offset` 越界、时间区间反向、ISO 时间解析失败、枚举值非法（`instType`/`bar`/`klineType`/`ratioType`/`period`）、缺少必填查询参数、请求体展开规则冲突 |
| 404 | `NOT_FOUND` | `GET /api/v1/instruments/{instId}` 品种不存在；`GET /api/v1/backfill/jobs/{id}` 作业不存在；按 `instType` 展开时没有任何品种；**未匹配的路径或缺失的静态资源**（`NoResourceFoundException`，如 `GET /nope`、`GET /favicon.ico`），`msg` 形如 `no resource: /nope` |
| 409 | `CONFLICT` | 所有 task 都已在 `PENDING`/`RUNNING`/`PAUSED` 且同 `data_type` + `task_key` 重复；`pause`/`resume`/`cancel` 的状态前置条件不满足 |
| 422 | `INVALID_PARAM` | 任务数超过 `okx.backfill.max-tasks-per-job`；上游返回参数类错误（OKX `51000`/`51001`/`51002`/`51010`/`51014`）或区间越界（`50030`） |
| 429 | `RATE_LIMITED` | 上游返回 `429` 或 OKX 限流码（`50011`/`50013`/`50061`）且重试耗尽 |
| 502 | `UPSTREAM_ERROR` | OKX 返回 HTTP 5xx、无法解析的响应体，或未分类的上游错误码，且重试耗尽 |
| 500 | `INTERNAL_ERROR` | 未预期异常（同时会打 error 日志） |

错误响应示例：

```json
{ "code": "INVALID_PARAM", "msg": "limit must be >= 1", "data": null }
```

```json
{ "code": "NOT_FOUND", "msg": "no resource: /nope", "data": null }
```

未匹配路径保持 404 语义，不会被兜底的 `Exception` 处理器吞成 500。

## 端点索引

| 方法 | 路径 | 用途 | 成功状态 |
| --- | --- | --- | --- |
| GET | `/api/v1/coins` | 平台币种清单 | 200 |
| GET | `/api/v1/instruments` | 交易品种列表 | 200 |
| GET | `/api/v1/instruments/{instId}` | 单个品种 | 200 |
| GET | `/api/v1/klines` | K 线查询 | 200 |
| GET | `/api/v1/funding-rates` | 资金费率查询 | 200 |
| GET | `/api/v1/ratios/long-short` | 多空比 / 精英持仓查询 | 200 |
| GET | `/api/v1/stats/open-interest-volume` | 合约持仓量与成交量 | 200 |
| GET | `/api/v1/stats/taker-volume` | 主动买入/卖出量 | 200 |
| GET | `/api/v1/stats/open-interest` | 持仓量快照 | 200 |
| POST | `/api/v1/sync/instruments` | 同步品种并重建币种 | 200 |
| POST | `/api/v1/sync/basic` | 同步品种 + 建首批补拉作业 | 200 |
| GET | `/api/v1/sync/status` | 数据面/补拉面概览 | 200 |
| POST | `/api/v1/backfill/klines` | 提交 K 线补拉作业 | **202** |
| POST | `/api/v1/backfill/funding-rates` | 提交资金费率补拉作业 | **202** |
| POST | `/api/v1/backfill/ratios` | 提交多空比补拉作业 | **202** |
| POST | `/api/v1/backfill/market-stats` | 提交持仓量/成交量/主动买卖补拉作业 | **202** |
| GET | `/api/v1/backfill/jobs` | 作业列表 | 200 |
| GET | `/api/v1/backfill/jobs/{id}` | 作业详情（可含子任务） | 200 |
| POST | `/api/v1/backfill/jobs/{id}/pause` | 暂停作业 | 200 |
| POST | `/api/v1/backfill/jobs/{id}/resume` | 恢复作业 | 200 |
| POST | `/api/v1/backfill/jobs/{id}/cancel` | 取消作业 | 200 |

---

## GET /api/v1/coins

读取派生表 `okx_coin`（每次品种同步后整体重建）。

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `channel` | string | 否 | — | 与 `channels` 数组做 `= ANY` 匹配，取值为 `SPOT`/`MARGIN`/`SWAP`/`FUTURES`/`OPTION` |
| `q` | string | 否 | — | `coin ILIKE '%q%'` 模糊匹配 |
| `limit` | int | 否 | 100 | 上限 1000，超出截断 |
| `offset` | int | 否 | 0 | ≥ 0 |

排序：`inst_count DESC, coin ASC`。

```bash
curl -s "http://localhost:18080/api/v1/coins?channel=SWAP&limit=2"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "coin": "BTC",
      "channels": ["FUTURES", "MARGIN", "OPTION", "SPOT", "SWAP"],
      "roles": ["BASE", "QUOTE", "SETTLE"],
      "quoteCcys": ["USDC", "USDT", "USD"],
      "instCount": 153,
      "spotInstCount": 6,
      "swapInstCount": 3,
      "futuresInstCount": 12,
      "optionInstCount": 132,
      "marginInstCount": 0
    }
  ]
}
```

`channels` / `roles` / `quoteCcys` 来自 PostgreSQL `text[]`，序列化成 JSON 数组；无数据时为 `[]`。

注意两个实测细节：上游 `baseCcy`/`quoteCcy` 对部分品种返回**空串**而不是 null，因此数组里可能出现 `""`（例如 `USD` 币种的 `quoteCcys` 为 `["", "USD"]`）；`roles` 只包含实际出现过的角色，没有 `base_ccy` 的币种就不会有 `BASE`（同上例为 `["QUOTE", "SETTLE"]`）。

---

## GET /api/v1/instruments

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instType` | string | 否 | — | `SPOT` / `MARGIN` / `SWAP` / `FUTURES` / `OPTION`，精确匹配 |
| `baseCcy` | string | 否 | — | 精确匹配 |
| `state` | string | 否 | — | 精确匹配（OKX 取值如 `live`、`suspend`、`expired`） |
| `limit` | int | 否 | 100 | 上限 1000 |
| `offset` | int | 否 | 0 | ≥ 0 |

排序：`inst_id ASC`。`instType` 这里只做字符串过滤，不会做枚举校验（不存在的值返回空数组而非 400）。

```bash
curl -s "http://localhost:18080/api/v1/instruments?instType=SWAP&baseCcy=BTC&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "instId": "BTC-USDT-SWAP",
      "instType": "SWAP",
      "instFamily": "BTC-USDT",
      "uly": "BTC-USDT",
      "baseCcy": "BTC",
      "quoteCcy": "USDT",
      "settleCcy": "USDT",
      "ctType": "linear",
      "ctVal": 0.01,
      "ctValCcy": "BTC",
      "ctMult": 1,
      "lever": 125,
      "tickSz": 0.1,
      "lotSz": 0.01,
      "minSz": 0.01,
      "listTimeMs": 1611916828000,
      "expTimeMs": null,
      "state": "live",
      "ruleType": "normal",
      "category": "1",
      "instCategory": "1",
      "optionType": "",
      "alias": "",
      "raw": "{\"instId\":\"BTC-USDT-SWAP\",\"instType\":\"SWAP\",...}"
    }
  ]
}
```

注意 `raw` 是**上游原始 JSON 的字符串**（`jsonb` 列在仓储里以 `String` 取出），不是嵌套对象。
上例的 `"expTimeMs": null` 实际会被省略（non_null）；但 OKX 对不少字段返回的是**空串**，空串不是 null，会原样出现在响应里（例如 SWAP 品种的 `baseCcy`、`quoteCcy`、`optionType` 常常是 `""`）。

---

## GET /api/v1/instruments/{instId}

按 `inst_id` 主键精确查询。

```bash
curl -s "http://localhost:18080/api/v1/instruments/BTC-USDT-SWAP"
```

成功时 `data` 是单个品种对象（字段同上）；不存在时：

```json
{ "code": "NOT_FOUND", "msg": "instrument not found: BTC-XXX", "data": null }
```

---

## GET /api/v1/klines

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instId` | string | **是** | — | 缺失 → 400 |
| `bar` | string | **是** | — | 24 个枚举 code 之一，见下表；非法 → 400 `invalid bar: X` |
| `klineType` | string | 否 | `CANDLE` | `CANDLE` / `MARK`，大小写不敏感；传其它值 → 400 `invalid klineType: X (allowed: CANDLE,MARK)` |
| `startMs` / `endMs` | long | 否 | — | epoch 毫秒 |
| `start` / `end` | string | 否 | — | ISO-8601（带偏移） |
| `limit` | int | 否 | 500 | 上限 **5000** |
| `order` | string | 否 | `desc` | `asc` 时升序，其它值按 `desc` |

`bar` 白名单（`Bar` 枚举，24 个）：

| code | 含义 | 间隔(ms) | code | 含义 | 间隔(ms) |
| --- | --- | --- | --- | --- | --- |
| `1m` | 1 分钟 | 60000 | `1D` | 1 天 | 86400000 |
| `3m` | 3 分钟 | 180000 | `2D` | 2 天 | 172800000 |
| `5m` | 5 分钟 | 300000 | `3D` | 3 天 | 259200000 |
| `15m` | 15 分钟 | 900000 | `1W` | 1 周 | 604800000 |
| `30m` | 30 分钟 | 1800000 | `1M` | 1 月 | 2592000000 |
| `1H` | 1 小时 | 3600000 | `3M` | 3 月 | 7776000000 |
| `2H` | 2 小时 | 7200000 | `6Hutc` | 6 小时(UTC) | 21600000 |
| `4H` | 4 小时 | 14400000 | `12Hutc` | 12 小时(UTC) | 43200000 |
| `6H` | 6 小时 | 21600000 | `1Dutc` | 1 天(UTC) | 86400000 |
| `12H` | 12 小时 | 43200000 | `2Dutc` | 2 天(UTC) | 172800000 |
| `3Dutc` | 3 天(UTC) | 259200000 | `1Wutc` | 1 周(UTC) | 604800000 |
| `1Mutc` | 1 月(UTC) | 2592000000 | `3Mutc` | 3 月(UTC) | 7776000000 |

```bash
curl -s "http://localhost:18080/api/v1/klines?instId=BTC-USDT-SWAP&bar=1H&klineType=CANDLE&limit=2&order=asc"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "klineType": "CANDLE",
      "instId": "BTC-USDT-SWAP",
      "bar": "1H",
      "tsMs": 1735689600000,
      "instType": "SWAP",
      "open": 93456.1,
      "high": 94120.5,
      "low": 93100.0,
      "close": 93980.2,
      "vol": 120345,
      "volCcy": 1203.45,
      "volCcyQuote": 113000000,
      "confirm": 1
    }
  ]
}
```

`MARK` 没有成交量，响应中只有 `open/high/low/close/confirm`，量相关字段整体缺失。
K 线只覆盖永续合约（`instType=SWAP`），指数 K 线（`INDEX`）已下线。
`confirm=0` 表示该根 K 线仍在生成中，补拉时会被后续的 `confirm=1` 覆盖。

---

## GET /api/v1/funding-rates

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instId` | string | **是** | — | 缺失 → 400 |
| `startMs` / `endMs` | long | 否 | — | 过滤 `funding_time_ms` |
| `start` / `end` | string | 否 | — | ISO-8601 |
| `limit` | int | 否 | 500 | 上限 1000 |
| `order` | string | 否 | `desc` | `asc` 升序 |

```bash
curl -s "http://localhost:18080/api/v1/funding-rates?instId=BTC-USDT-SWAP&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "instId": "BTC-USDT-SWAP",
      "instType": "SWAP",
      "fundingTimeMs": 1735689600000,
      "fundingRate": 0.0001,
      "realizedRate": 0.000098,
      "method": "current_period",
      "formulaType": "with_rate",
      "settled": true,
      "premium": 0.00012,
      "interestRate": 0.0001,
      "nextFundingTimeMs": 1735718400000,
      "impactValue": 50000,
      "minFundingRate": -0.00375,
      "maxFundingRate": 0.00375,
      "tsMs": 1735689600123
    }
  ]
}
```

`settled` 的判定：上游 `settState`/`settledState` 为 `settled`，或返回了非空的 `realizedRate`。

---

## GET /api/v1/ratios/long-short

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `ratioType` | string | **是** | — | 4 个枚举之一，见下表；非法 → 400 |
| `scope` | string | **是** | — | `CCY` 类型传币种（如 `BTC`），`INST` 类型传 `instId` |
| `period` | string | **是** | — | `5m` / `1H` / `1D`；非法 → 400 `invalid period: X (allowed: 5m,1H,1D)` |
| `startMs` / `endMs` | long | 否 | — | epoch 毫秒 |
| `start` / `end` | string | 否 | — | ISO-8601 |
| `limit` | int | 否 | 500 | 上限 1000 |
| `order` | string | 否 | `desc` | `asc` 升序 |

| `ratioType` | 含义 | 上游 `scope_kind` |
| --- | --- | --- |
| `LSR_ACCOUNT_CCY` | 全平台多空账户数比值（按币） | `CCY` |
| `LSR_ACCOUNT_CONTRACT` | 单合约多空账户数比值 | `INST` |
| `TOP_TRADER_ACCOUNT` | 大户账户数多空比 | `INST` |
| `TOP_TRADER_POSITION` | 精英持仓量多空比 | `INST` |

```bash
curl -s "http://localhost:18080/api/v1/ratios/long-short?ratioType=TOP_TRADER_POSITION&scope=BTC-USDT-SWAP&period=1H&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "ratioType": "TOP_TRADER_POSITION",
      "scope": "BTC-USDT-SWAP",
      "period": "1H",
      "tsMs": 1735689600000,
      "scopeKind": "INST",
      "longShortRatio": 1.83
    }
  ]
}
```

---

## GET /api/v1/stats/open-interest-volume

合约持仓量 / 成交量（rubik，按币种维度，上游原始列 `[ts, oi, vol]`）。

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `ccy` | string | **是** | — | 如 `BTC` |
| `period` | string | **是** | — | `5m` / `1H` / `1D` |
| `startMs` / `endMs`、`start` / `end` | — | 否 | — | 同上 |
| `limit` | int | 否 | 500 | 上限 1000 |
| `order` | string | 否 | `desc` | `asc` 升序 |

```bash
curl -s "http://localhost:18080/api/v1/stats/open-interest-volume?ccy=BTC&period=1H&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [ { "ccy": "BTC", "period": "1H", "tsMs": 1735689600000, "oi": 1839201.4, "vol": 33245.8 } ]
}
```

---

## GET /api/v1/stats/taker-volume

主动买入/卖出量（rubik 原始列 `[ts, sellVol, buyVol]`）。

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `scopeKind` | string | 否 | `CCY` | 服务端做 `toUpperCase` 后直接用于过滤，取值 `CCY` 或 `INST`（其它值不会报错，只是查不到数据） |
| `scope` | string | **是** | — | `scopeKind=CCY` 时为币种，`INST` 时为 `instId` |
| `period` | string | **是** | — | `5m` / `1H` / `1D` |
| `startMs` / `endMs`、`start` / `end` | — | 否 | — | 同上 |
| `limit` | int | 否 | 500 | 上限 1000 |
| `order` | string | 否 | `desc` | `asc` 升序 |

```bash
curl -s "http://localhost:18080/api/v1/stats/taker-volume?scopeKind=CCY&scope=BTC&period=1H&limit=1"
curl -s "http://localhost:18080/api/v1/stats/taker-volume?scopeKind=INST&scope=BTC-USDT-SWAP&period=1H&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "scopeKind": "CCY",
      "scope": "BTC",
      "marketType": "CONTRACTS",
      "period": "1H",
      "tsMs": 1735689600000,
      "sellVol": 12045.3,
      "buyVol": 15210.9
    }
  ]
}
```

`marketType` 由写入侧决定，也是主键的一部分：

| 口径 | `marketType` | 规则 |
| --- | --- | --- |
| `scopeKind=CCY` | 恒为 `CONTRACTS` | 上游 `/rubik/stat/taker-volume` 只接受 `SPOT` / `CONTRACTS`，本服务按币采集固定用 `CONTRACTS` |
| `scopeKind=INST` | 衍生品 `CONTRACTS`；`SPOT`/`MARGIN` 等非衍生品 `SPOT`；`instType` 未知 → 空串 `''` | 按该 `instId` 在 `okx_instrument` 里的 `inst_type` 判定：`SWAP`/`FUTURES`/`OPTION` → `CONTRACTS`，`SPOT`/`MARGIN` → `SPOT`；`instId` 未入库、查不到 `inst_type` 时不猜市场，写空串表示未知（`BackfillPlanner.takerMarketType`） |

写入兜底：`null` 会被归一成空串（列 `NOT NULL` 且参与唯一键），所以 `INST` 口径的未知市场与 `nullToEmpty` 兜底最终都是 `''`。查询时想同时取一个 `scope` 的多个口径，需要按 `marketType` 分别过滤。

---

## GET /api/v1/stats/open-interest

持仓量快照（每采集一次一行，无历史区间回填）。

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instId` | string | **是** | — | 合约 `instId` |
| `startMs` / `endMs`、`start` / `end` | — | 否 | — | 过滤 `ts_ms` |
| `limit` | int | 否 | 500 | 上限 1000 |
| `order` | string | 否 | `desc` | `asc` 升序 |

```bash
curl -s "http://localhost:18080/api/v1/stats/open-interest?instId=BTC-USDT-SWAP&limit=1"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [ { "instId": "BTC-USDT-SWAP", "instType": "SWAP", "tsMs": 1735689600000, "oi": 1839201.4, "oiCcy": 18392.01, "oiUsd": 1710000000 } ]
}
```

---

## POST /api/v1/sync/instruments

拉取上游品种清单并 upsert 到 `okx_instrument`，随后**整体重建** `okx_coin`。请求体可省略。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instTypes` | string[] | 否 | 全部 5 类 | 元素须为 `SPOT`/`MARGIN`/`SWAP`/`FUTURES`/`OPTION`，非法 → 400 |
| `includeOptions` | boolean | 否 | `okx.sync.include-options`（true） | 是否展开 OPTION |
| `families` | string[] | 否 | — | 指定 OPTION 家族；缺省时用本次 SWAP/FUTURES 的 `instFamily` 去重，仍为空则取库中已有家族 |

```bash
curl -s -X POST "http://localhost:18080/api/v1/sync/instruments" \
  -H "Content-Type: application/json" -d '{"instTypes":["SPOT","SWAP","FUTURES"]}'
```

```json
{
  "code": "0",
  "msg": "",
  "data": {
    "instrumentsUpserted": 2028,
    "coinsUpserted": 421,
    "durationMs": 18734,
    "perType": { "SPOT": 1211, "SWAP": 532, "FUTURES": 285 },
    "optionFamilies": []
  }
}
```

行为要点：

- 未传 `instTypes` 时依次同步 `SPOT, MARGIN, SWAP, FUTURES`，再（`includeOptions=true` 时）按家族同步 `OPTION`。
- OPTION 必须带 `instFamily`，所以先收集家族再逐家族拉取；`perType.OPTION` 是各家族条数之和。
- 同步完成后 `okx_coin` 会 `DELETE` 后按 `okx_instrument` 全量重算，币种消失或新增都会反映。
- 该接口是同步阻塞调用，SPOT+SWAP+FUTURES+OPTION 全量通常在十几秒量级（受客户端限流约束：instruments 组每 2 秒 5 次）。

---

## POST /api/v1/sync/basic

组合入口：先同步品种，再为头部品种自动创建 K 线 / 资金费率 / 多空比补拉作业。请求体可省略。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instTypes`、`includeOptions`、`families` | — | 否 | 同上一节 | 透传给品种同步 |
| `instType` | string | 否 | `SWAP` | 用于挑选补拉标的的品种类型，枚举校验，非法 → 400；非 `SWAP` 时 K 线与多空比会被跳过（见下方说明） |
| `maxInstruments` | int | 否 | 20 | 夹到 `[1, 500]` |
| `maxCcys` | int | 否 | 10 | 夹到 `[1, 100]` |
| `days` | int | 否 | 7 | 夹到 `[1, 365]`，决定 `[now-days, now]` 的补拉区间 |
| `bars` | string[] | 否 | `["1H"]` | 传给 K 线作业的 bar 列表 |
| `klineTypes` | string[] | 否 | `["CANDLE","MARK"]`（固定，与 `instType` 无关） | 只允许 `CANDLE` / `MARK` |
| `includeFunding` | boolean | 否 | `true` | 非衍生品（`SPOT` / `MARGIN`）会被跳过并返回原因字符串 |
| `includeRatios` | boolean | 否 | `true` | 非 `SWAP` 会被跳过并返回原因字符串 |
| `ratioTypes` | string[] | 否 | `["LSR_ACCOUNT_CCY","TOP_TRADER_POSITION"]` | — |

```bash
curl -s -X POST "http://localhost:18080/api/v1/sync/basic" \
  -H "Content-Type: application/json" -d '{"instType":"SWAP","maxInstruments":10,"days":3}'
```

```json
{
  "code": "0",
  "msg": "",
  "data": {
    "instrumentSync": { "instrumentsUpserted": 2028, "coinsUpserted": 421, "durationMs": 18210, "perType": { "SPOT": 1211, "SWAP": 532, "FUTURES": 285 }, "optionFamilies": [] },
    "jobs": { "klines": 7, "fundingRates": 8, "ratios": 9 }
  }
}
```

`jobs` 的值是两种形态之一：`long`（新建的 job id）、`"skipped: ..."`（该数据类型不适用，或同任务已在 `PENDING`/`RUNNING`/`PAUSED`）。

**只做永续合约的数据类型会按 `instType` 跳过，而不是报错。** 非 `SWAP` 时：

| `jobs` 键 | 取值 |
| --- | --- |
| `klines` | `"skipped: klines only support perpetual swap instruments (instType=SWAP)"` |
| `ratios` | `"skipped: long-short ratios only cover perpetual swap contracts (instType=SWAP)"` |
| `fundingRates` | `"skipped: funding rate only applies to derivatives"`（`SPOT`/`MARGIN`） |

所以 `POST /api/v1/sync/basic` 传 `{"instType":"SPOT"}` 仍然是 **200**：品种照常同步，三个作业键都是跳过说明：

```json
{
  "code": "0",
  "msg": "",
  "data": {
    "instrumentSync": { "instrumentsUpserted": 1211, "coinsUpserted": 478, "durationMs": 8203, "perType": { "SPOT": 1211 }, "optionFamilies": [] },
    "jobs": {
      "klines": "skipped: klines only support perpetual swap instruments (instType=SWAP)",
      "fundingRates": "skipped: funding rate only applies to derivatives",
      "ratios": "skipped: long-short ratios only cover perpetual swap contracts (instType=SWAP)"
    }
  }
}
```

若 `instType` 对应品种在库里一条都没有（`instIds` 为空），则连 `klines` 键都不会出现；
`includeFunding=false` / `includeRatios=false` 时对应的键同样不出现。
补拉本身的 SWAP 前置校验（`POST /api/v1/backfill/klines`）仍然是 **400**，两者不同：组合入口是跳过，作业入口是拒绝。
多空比作业只取前 10 个标的作为 `scopes`，币种侧取该品种下出现次数最多的 `maxCcys` 个 `baseCcy`。

---

## GET /api/v1/sync/status

无参数。返回行数统计、按品种聚合、补拉作业汇总与增量水位。

```bash
curl -s "http://localhost:18080/api/v1/sync/status"
```

```json
{
  "code": "0",
  "msg": "",
  "data": {
    "rows": { "instruments": 2028, "coins": 421, "klines": 158200, "fundingRates": 9600,
              "longShortRatios": 21000, "openInterestVolume": 4300, "takerVolume": 8600, "openInterest": 512 },
    "instrumentsByType": [ { "inst_type": "SPOT", "cnt": 1211 }, { "inst_type": "SWAP", "cnt": 532 } ],
    "backfillJobs": [ { "data_type": "KLINE", "status": "DONE", "jobs": 3 } ],
    "cursors": {}
  }
}
```

`rows` 的键固定为：`instruments`、`coins`、`klines`、`fundingRates`、`longShortRatios`、`openInterestVolume`、`takerVolume`、`openInterest`。
`instrumentsByType` 与 `backfillJobs` 是 SQL 原始列名（下划线风格），`cursors` 来自 `okx_sync_cursor`（当前没有写入方，通常为空对象）。

---

## POST /api/v1/backfill/klines

**返回 HTTP 202 Accepted**。请求体必填。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instIds` | string[] | 二选一 | — | 与 `instType` 互斥，同时给 → 400；都缺 → 400 |
| `instType` | string | 二选一 | — | 按该品种展开库里 `state=live` 的品种 |
| `baseCcy` | string | 否 | — | 仅在按 `instType` 展开时生效 |
| `bars` | string[] | **是** | — | 非空，元素走 `Bar` 白名单；缺失 → 400 `bars is required` |
| `klineTypes` | string[] | **是** | — | 非空，`CANDLE`/`MARK`；缺失 → 400 `klineTypes is required` |
| `startMs` | long | 否 | `endMs - 7 天` | — |
| `endMs` | long | 否 | 当前时间 | — |
| `pageLimit` | int | 否 | `okx.backfill.page-limit`(300) | `≥1`，硬上限 300 |

**品种校验**：每个 `instId` 必须在 `okx_instrument` 里是 `instType=SWAP`（永续合约），否则 400：

- 品种存在但不是 SWAP：`klines only support perpetual swap instruments (instType=SWAP), got <instId> (instType=XXX)`
- 品种不存在：`klines only support perpetual swap instruments (instType=SWAP), got <instId> (instrument not found in okx_instrument, run POST /api/v1/sync/instruments first)`

因此按 `instType` 展开时只能传 `SWAP`；`CANDLE` 与 `MARK` 都可以配任意 SWAP 品种。

```bash
curl -s -X POST "http://localhost:18080/api/v1/backfill/klines" \
  -H "Content-Type: application/json" \
  -d '{"instIds":["BTC-USDT-SWAP"],"bars":["1H","1D"],"klineTypes":["CANDLE","MARK"],"startMs":1735689600000,"endMs":1738368000000}'
```

```json
{ "code": "0", "msg": "", "data": { "jobId": 12, "taskTotal": 4 } }
```

HTTP 头为 `202 Accepted`；一个 `instId × bar × klineType` 组合生成一个 task，因此上例是 1×2×2=4 个。

---

## POST /api/v1/backfill/funding-rates

**返回 202**。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `instIds` | string[] | 二选一 | — | 与 `instType` 互斥 |
| `instType` | string | 二选一 | — | 按品种展开 |
| `startMs` | long | 否 | `endMs - 90 天` | — |
| `endMs` | long | 否 | 当前时间 | — |

```bash
curl -s -X POST "http://localhost:18080/api/v1/backfill/funding-rates" \
  -H "Content-Type: application/json" -d '{"instType":"SWAP","startMs":1735689600000}'
```

```json
{ "code": "0", "msg": "", "data": { "jobId": 13, "taskTotal": 532 } }
```

一个 `instId` 一个 task。翻到空页即判定到达上游约 96 天的保留边界，task 被标记 `truncated=true` 后正常结束。

---

## POST /api/v1/backfill/ratios

**返回 202**。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `ratioTypes` | string[] | **是** | — | 非空，4 个枚举之一 |
| `periods` | string[] | **是** | — | 非空，`5m`/`1H`/`1D` |
| `ccys` | string[] | 条件 | — | `ratioType` 为 `CCY` 类型时必填（否则 400 `ccys is required`） |
| `scopes` | string[] | 条件 | — | `ratioType` 为 `INST` 类型时必填（否则 400 `scopes is required`） |
| `startMs` | long | 否 | `endMs - max(period 的保留期, 1 天)` | — |
| `endMs` | long | 否 | 当前时间 | — |

一个 `ratioType × scope × period` 生成一个 task。服务端会按 period 的保留期裁剪起止时间。

```bash
curl -s -X POST "http://localhost:18080/api/v1/backfill/ratios" \
  -H "Content-Type: application/json" \
  -d '{"ratioTypes":["LSR_ACCOUNT_CCY"],"ccys":["BTC","ETH"],"periods":["1H","1D"]}'
```

```json
{ "code": "0", "msg": "", "data": { "jobId": 14, "taskTotal": 4 } }
```

---

## POST /api/v1/backfill/market-stats

**返回 202**。一个接口覆盖持仓量、成交量、主动买卖三类 rubik 统计与持仓量快照。

| 字段 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `kinds` | string[] | 否 | `["OPEN_INTEREST_VOLUME","TAKER_VOLUME","OPEN_INTEREST"]` | 元素须为这三个之一，其它 → 400 `unknown kind: X` |
| `periods` | string[] | 否 | `["1H"]` | `5m`/`1H`/`1D` |
| `ccys` | string[] | 条件 | — | `OPEN_INTEREST_VOLUME` 必填；`TAKER_VOLUME` 用于 `CCY` 维度（可空） |
| `instIds` | string[] | 条件 | — | `TAKER_VOLUME` 用于 `INST` 维度（可空）；`OPEN_INTEREST` 缺省时取库里全部 `SWAP` |
| `startMs` | long | 否 | `endMs - max(period 保留期, 1 天)` | — |
| `endMs` | long | 否 | 当前时间 | — |

各样式的 `task_key` 前缀：`OI_VOLUME:<CCY>:<period>`、`TAKER_VOLUME:CCY:<CCY>:<period>`、`TAKER_VOLUME:INST:<instId>:<period>`、`OPEN_INTEREST:<instId>`。

```bash
curl -s -X POST "http://localhost:18080/api/v1/backfill/market-stats" \
  -H "Content-Type: application/json" \
  -d '{"kinds":["OPEN_INTEREST_VOLUME","TAKER_VOLUME"],"ccys":["BTC"],"instIds":["BTC-USDT-SWAP"],"periods":["1H"]}'
```

```json
{ "code": "0", "msg": "", "data": { "jobId": 15, "taskTotal": 3 } }
```

上例：`OI_VOLUME:BTC:1H`、`TAKER_VOLUME:CCY:BTC:1H`、`TAKER_VOLUME:INST:BTC-USDT-SWAP:1H` 共 3 个 task。

共同行为（四个补拉端点）：

- 已被活跃作业覆盖的 task（同 `data_type` + `task_key`，且作业状态为 `PENDING`/`RUNNING`/`PAUSED`）会被剔除；全部被剔除时返回 **409**（`all N task(s) are already pending or running`）。
- 展开后的 task 数超过 `okx.backfill.max-tasks-per-job`（默认 5000）→ **422**（`request expands to N tasks which exceeds ...`）。
- `startMs >= endMs` → 400（`startMs must be earlier than endMs`）。
- 作业提交后立即返回，执行由 `okx.backfill.worker-threads`（默认 8）个线程异步推进。

---

## GET /api/v1/backfill/jobs

| 参数 | 类型 | 必填 | 默认 | 约束 |
| --- | --- | --- | --- | --- |
| `dataType` | string | 否 | — | `KLINE`/`FUNDING_RATE`/`LONG_SHORT_RATIO`/`OPEN_INTEREST_VOLUME`/`TAKER_VOLUME`/`OPEN_INTEREST`，精确匹配（不做枚举校验） |
| `status` | string | 否 | — | `PENDING`/`RUNNING`/`PAUSED`/`DONE`/`FAILED`/`CANCELED` |
| `limit` | int | 否 | 50 | 上限 500 |
| `offset` | int | 否 | 0 | ≥ 0 |

排序：`id DESC`。

```bash
curl -s "http://localhost:18080/api/v1/backfill/jobs?status=RUNNING&limit=10"
```

```json
{
  "code": "0",
  "msg": "",
  "data": [
    {
      "id": 15,
      "dataType": "OPEN_INTEREST_VOLUME",
      "request": "{\"kinds\":[\"OPEN_INTEREST_VOLUME\"],...}",
      "status": "RUNNING",
      "taskTotal": 3,
      "taskDone": 1,
      "taskFailed": 0,
      "rowsWritten": 744,
      "createdAt": "2026-01-10T08:12:31.412Z",
      "startedAt": "2026-01-10T08:12:31.512Z"
    }
  ]
}
```

`request` 是提交时的原始请求体 JSON 文本（`jsonb::text`），`error` / `finishedAt` 为 null 时整体缺失。

`error` 语义是「取值即真相」：作业每次状态变更都会重写该列，**转 `DONE` 时传 `null` 会清空历史错误**（所以成功作业不会残留 `N task(s) failed`）；`FAILED` 作业保留 `N task(s) failed`，重启中断的作业是 `interrupted by restart`（`auto-resume` 接手置回 `PENDING` 时同样被清空）。

---

## GET /api/v1/backfill/jobs/{id}

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `includeTasks` | boolean | 否 | `false` | 为 `true` 时 `data.tasks` 返回子任务数组；为 `false` 时 `tasks` 字段整体缺失（`JobDetail` 未标注 `@JsonInclude`，遵循全局 `non_null`） |

响应 `data` 固定为 `{ job, tasks }` 两个键：

```bash
curl -s "http://localhost:18080/api/v1/backfill/jobs/15?includeTasks=true"
```

```json
{
  "code": "0",
  "msg": "",
  "data": {
    "job": { "id": 15, "dataType": "OPEN_INTEREST_VOLUME", "status": "RUNNING", "taskTotal": 3, "taskDone": 1, "taskFailed": 0, "rowsWritten": 744, "createdAt": "2026-01-10T08:12:31.412Z" },
    "tasks": [
      {
        "id": 41,
        "taskKey": "OI_VOLUME:BTC:1H",
        "status": "RUNNING",
        "cursorMs": 1735689600000,
        "pageCount": 7,
        "rowsWritten": 744,
        "attempts": 1,
        "truncated": false
      }
    ]
  }
}
```

作业不存在 → 404（`backfill job not found: <id>`）。`lastError` 为 null 时缺失。

---

## POST /api/v1/backfill/jobs/{id}/pause

把作业置为 `PAUSED`；正在执行的 task 在**下一个页边界**停止，游标保留在 `okx_backfill_task.cursor_ms`（续跑从该点继续）。K 线/资金费率的游标是"已翻到的最早 `ts`"，rubik 统计类的游标是"已推进到的窗口起点"（首次执行从 `startMs` 起算，不沿用任务行里预置的 `endMs`）。

前置条件：当前状态必须是 `PENDING` 或 `RUNNING`，否则 **409**：

```json
{ "code": "CONFLICT", "msg": "only PENDING or RUNNING jobs can be paused, job 15 is DONE", "data": null }
```

成功返回 200，`data` 为更新后的 job 行。

## POST /api/v1/backfill/jobs/{id}/resume

前置条件：当前状态必须是 `PAUSED` 或 `FAILED`，否则 409（`only PAUSED or FAILED jobs can be resumed, ...`）。
动作：把该作业下 `FAILED` 的 task 重置为 `PENDING`、刷新计数、把作业置回 `PENDING` 并重新调度。
成功返回 200 + 更新后的 job 行。

## POST /api/v1/backfill/jobs/{id}/cancel

把作业置为 `CANCELED`（终态）。`DONE`/`CANCELED` 状态下调用 → 409（`job 15 already finished with status DONE`）。
成功返回 200 + job 行。已写入的数据不会回滚。

```bash
curl -s -X POST "http://localhost:18080/api/v1/backfill/jobs/15/pause"
curl -s -X POST "http://localhost:18080/api/v1/backfill/jobs/15/resume"
curl -s -X POST "http://localhost:18080/api/v1/backfill/jobs/15/cancel"
```

```json
{ "code": "0", "msg": "", "data": { "id": 15, "dataType": "OPEN_INTEREST_VOLUME", "status": "PAUSED", "taskTotal": 3, "taskDone": 1, "taskFailed": 0, "rowsWritten": 744, "createdAt": "2026-01-10T08:12:31.412Z" } }
```

### 作业状态机

```text
PENDING ──claim──> RUNNING ──全部 task 结束(无失败)──> DONE
   │                  │
   │                  ├──有 task 失败──> FAILED ──resume──> PENDING
   ├──pause──> PAUSED ─┘ (页边界生效)   └──resume──> PENDING
   └──cancel──> CANCELED（终态）
```

`JobStatus.terminal()` 只把 `DONE` / `CANCELED` 视为终态；`FAILED` 仍可 `resume`。

转换到 `DONE` 时 `error` 被清空为 `NULL`（见上文「取值即真相」），因此 `status=DONE` 的作业在 `GET /api/v1/backfill/jobs` 里不会带 `error` 字段；主动 `pause` 同样传 `null`，只有 `FAILED`（以及重启中断瞬间尚未被 `auto-resume` 接手的 `PAUSED`）才可能有值。

## 子任务状态

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 待执行（`pause` 时被重新入队的 task 也回到这里） |
| `RUNNING` | 正在执行，`attempts` 已 +1 |
| `DONE` | 完成（`truncated=true` 表示被上游保留期截断，不是错误） |
| `FAILED` | 连续 3 次失败后终止，`last_error` 有原因 |
| `SKIPPED` | 保留值，当前执行路径不会产生 |
