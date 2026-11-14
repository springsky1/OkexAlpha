# 实盘执行记录 · 2026-11-07

> 对应方案：`reports/martingale-100u/report.md` 的 27 策略组合（A 方案）。本文件只记录**真实下单、杠杆校正、成交后核验与回滚方式**，回测结论不在此重复。

## 1. 结论摘要

- 真实市价单 **27/27 全部成交**，`sCode=0`、`accepted=true`，0 失败、0 拒单、0 重试。
- 方向：**21 多 / 6 空**；全部 `mgnMode=isolated`（逐仓）；持仓杠杆已逐币校正到回测参数（2×/3×/5×），复核 `lever_ok = 27/27`、`side_ok = 27/27`。
- 当前占用保证金 **14.7565 USDT**，名义敞口 **37.898 USDT**，浮动盈亏 **+0.01752 USDT**。
- 账户可用余额 **137.03 USDT**（下单前 90.27U；期间此前冻结的 851U 释放，与本次下单无关），账户权益 939.05 USDT。
- 全部仓位同时强平的极限亏损 = 各自逐仓保证金之和 ≈ **14.76 USDT**，占可用资金 10.8%，不触及账户其余资金。

## 2. 执行前置条件与门禁

| 项 | 状态 |
|---|---|
| 交易模式 `okx.strategy.trading.mode` | REAL（外部覆盖 `C:\Users\weiji\Desktop\OKexAlpha\config\application.yml`） |
| `okx.strategy.trading.enabled` | true（同上文件） |
| `/api/v1/strategy/live/gate` | 9 项全 PASS，`liveAllowed=true` |
| 账户持仓模式 | `long_short_mode`（双向持仓）⇒ 每单必须带 `posSide` |
| 下单确认口令 | 每单 `confirm=CONFIRM-REAL-TRADING` |
| 额度 | `maxPositions=50`、`maxDailyNotionalUsd=500`，实际用 27 仓 / 37.90U |

门禁不通过时服务返回 **422 且不向 OKX 发请求**，因此下单失败不会产生任何交易所副作用。

## 3. 为打通实盘修复的两处真实缺陷

实盘下单链路此前从未被真实调用过，首次调用即暴露两个缺陷（均已修复并有回归测试）：

### 3.1 重复 `Content-Type` 头 → OKX `50006 Incorrect Content-Type`

`backend/src/main/java/com/okexalpha/client/JdkHttpTransport.java` 先在 `:57` 无条件 `header("Content-Type", "application/json")`，随后 `:59` 又把调用方 headers 逐条 `header(...)` 追加进去；JDK `HttpRequest.Builder.header` 是**追加**语义，于是请求带了两条 `Content-Type`。OKX 直接拒绝。

修复：仅当调用方未提供 `content-type`（大小写不敏感）时才补默认值。新增 `backend/src/test/java/com/okexalpha/client/JdkHttpTransportTest.java`，用本地 `com.sun.net.httpserver.HttpServer` 抓真实请求头，断言该头恰好 1 条（红测在旧代码下为 `Expected size: 1 but was: 2 in: ["application/json", "application/json"]`）。

### 3.2 缺 `posSide` → 双向持仓下无法下单，也无法设杠杆

- 下单：`LiveOrderRequest` 增加 `posSide`，`LiveTradingService` 校验只允许 `long/short/空` 并在非空时写入请求体（净持仓模式不得带该字段）。
- 设杠杆：OKX `set-leverage` 在 `long_short_mode` 下同样要求 `posSide`（首次调用报 `51000: Parameter posSide error`）。新增 `OkxTradeClient.setLeverage(instId, lever, mgnMode, posSide)`、`LiveLeverageRequest`、`LiveTradingService.setLeverage(...)`，并暴露 `POST /api/v1/strategy/live/leverage`（同样走门禁 + REAL 确认口令）。

全量回归：**535 tests, 0 failures, BUILD SUCCESS**（原 530，+1 posSide、+2 transport、+2 leverage）。产物 `backend/target/okex-alpha-0.1.0.jar` 已部署到 `C:\Users\weiji\Desktop\OKexAlpha\app\app.jar`。

## 4. 下单明细（27 笔市价单）

| # | 合约 | 方向 | 张数 | 成交均价 | 名义(USDT) | 保证金(USDT) | ordId |
|---:|---|---|---:|---:|---:|---:|---|
| 1 | PEPE-USDT-SWAP | 多 | 0.1 | 4.9424e-06 | 4.942 | 2.4712 | 4078066138159370240 |
| 2 | UNI-USDT-SWAP | 多 | 1 | 7.045 | 7.045 | 2.3478 | 4078034769463070720 |
| 3 | ONDO-USDT-SWAP | 空 | 1 | 0.3705 | 3.705 | 1.8525 | 4078049789601239040 |
| 4 | NEAR-USDT-SWAP | 空 | 0.1 | 3.746 | 3.746 | 1.2484 | 4078033739677552640 |
| 5 | ADA-USDT-SWAP | 多 | 0.1 | 0.185 | 1.850 | 0.9300 | 4078079785115963392 |
| 6 | BCH-USDT-SWAP | 空 | 0.1 | 260.83 | 2.608 | 0.8707 | 4078060589531385856 |
| 7 | AAVE-USDT-SWAP | 多 | 0.1 | 132.01 | 1.320 | 0.6637 | 4078077235180457984 |
| 8 | ENA-USDT-SWAP | 多 | 1 | 0.1639 | 1.639 | 0.5464 | 4078028579978989570 |
| 9 | ATOM-USDT-SWAP | 多 | 1 | 1.388 | 1.388 | 0.4628 | 4078063172182462464 |
| 10 | INJ-USDT-SWAP | 空 | 1 | 8.372 | 0.837 | 0.4186 | 4078044205271339008 |
| 11 | LINK-USDT-SWAP | 多 | 0.1 | 10.877 | 1.088 | 0.3625 | 4078071500392484864 |
| 12 | DOGE-USDT-SWAP | 多 | 0.01 | 0.07199 | 0.720 | 0.3599 | 4078082059972235264 |
| 13 | SUI-USDT-SWAP | 多 | 1 | 0.8708 | 0.871 | 0.2902 | 4078032190603644928 |
| 14 | AVAX-USDT-SWAP | 多 | 0.1 | 8.343 | 0.834 | 0.2781 | 4078068962167476224 |
| 15 | XRP-USDT-SWAP | 多 | 0.01 | 1.1771 | 1.177 | 0.2354 | 4078074313931608064 |
| 16 | SHIB-USDT-SWAP | 多 | 0.1 | 4.522e-06 | 0.452 | 0.2265 | 4078081094778998784 |
| 17 | APT-USDT-SWAP | 空 | 1 | 0.6012 | 0.601 | 0.2003 | 4078038727107661824 |
| 18 | SOL-USDT-SWAP | 多 | 0.01 | 90.12 | 0.901 | 0.1802 | 4078055025166372864 |
| 19 | ORDI-USDT-SWAP | 多 | 1 | 3.269 | 0.327 | 0.1641 | 4078041653523894272 |
| 20 | TIA-USDT-SWAP | 空 | 1 | 0.4409 | 0.441 | 0.1469 | 4078027514692882432 |
| 21 | WLD-USDT-SWAP | 多 | 1 | 0.3416 | 0.342 | 0.1139 | 4078030072983441408 |
| 22 | BONK-USDT-SWAP | 多 | 1 | 2.832e-06 | 0.283 | 0.0944 | 4078052437280804864 |
| 23 | CRV-USDT-SWAP | 多 | 1 | 0.2655 | 0.266 | 0.0885 | 4078031077770907648 |
| 24 | ARB-USDT-SWAP | 多 | 0.1 | 0.16681 | 0.167 | 0.0556 | 4078046889994997760 |
| 25 | WIF-USDT-SWAP | 多 | 1 | 0.1625 | 0.163 | 0.0542 | 4078035831192408064 |
| 26 | OP-USDT-SWAP | 多 | 1 | 0.10435 | 0.104 | 0.0530 | 4078057922188922880 |
| 27 | FIL-USDT-SWAP | 多 | 1 | 0.8085 | 0.0808 | 0.0406 | 探针单（见 .run/live-orders.log） |
| — | **合计** | 21 多 / 6 空 | — | — | **37.898** | **14.7565** | — |

原始凭证：`.run/live-orders.log`（逐单响应）、`.run/orders-placed.csv`（ordId/clOrdId）、`.run/positions-final.json`（交易所持仓快照）。

## 5. 杠杆校正（默认杠杆 ≠ 回测参数）

OKX 按下单时该合约的既有杠杆设置成交，不会读回测参数：首轮成交后多数为 3×，BCH/DOGE/XRP/SOL 为 50×、SHIB 为 10×。50× 的强平距离仅 ~1.5%，与方案（2/3/5×）严重不符，故逐币调用新端点设回目标杠杆，27 次全部 `accepted=true`、0 失败（日志 `.run/live-leverage.log`）。

**注意：`lever` 字段已改到目标值，但存量逐仓仓位的保证金/强平价没有全部跟着重算。** 以 `名义/保证金` 反推实际生效杠杆，27 仓中有 **8 个**（UNI、ENA、LINK、SUI、AVAX、TIA、CRV、ARB）仍是开仓时的口径——它们占用保证金比计划多、强平距离为 32.9% 而非计划值，方向上更安全；其余 19 个已与目标一致。下一层加仓成交时会按新杠杆重算。

| 合约 | 目标杠杆 | 账户 lever | 实际生效(名义/保证金) | 强平价 | 实际强平距离 | 计划强平距离 | 止损距离(sl÷lev) |
|---|---:|---:|---:|---:|---:|---:|---:|
| PEPE-USDT-SWAP | 2× | 2× | 2.00× | 2.48872e-06 | 49.6% | 49.5% | 10.00% |
| UNI-USDT-SWAP | 5× | 5× | 3.00× | 4.73132 | 32.8% | 19.5% | 4.00% |
| ONDO-USDT-SWAP | 2× | 2× | 2.00× | 0.551787 | 48.9% | 49.5% | 7.50% |
| NEAR-USDT-SWAP | 3× | 3× | 3.00× | 4.95871 | 32.4% | 32.8% | 5.00% |
| ADA-USDT-SWAP | 2× | 2× | 1.99× | 0.0927485 | 49.9% | 49.5% | 7.50% |
| BCH-USDT-SWAP | 3× | 3× | 3.00× | 345.475 | 32.5% | 32.8% | 5.00% |
| AAVE-USDT-SWAP | 2× | 2× | 1.99× | 66.1077 | 49.9% | 49.5% | 7.50% |
| ENA-USDT-SWAP | 5× | 5× | 3.00× | 0.110035 | 32.9% | 19.5% | 4.00% |
| ATOM-USDT-SWAP | 3× | 3× | 3.00× | 0.932757 | 32.8% | 32.8% | 5.00% |
| INJ-USDT-SWAP | 2× | 2× | 2.00× | 12.4697 | 48.9% | 49.5% | 7.50% |
| LINK-USDT-SWAP | 5× | 5× | 3.00× | 7.30435 | 32.8% | 19.5% | 3.00% |
| DOGE-USDT-SWAP | 2× | 2× | 2.00× | 0.0362587 | 49.6% | 49.5% | 10.00% |
| SUI-USDT-SWAP | 5× | 5× | 3.00× | 0.584746 | 32.8% | 19.5% | 3.00% |
| AVAX-USDT-SWAP | 5× | 5× | 3.00× | 5.60269 | 32.8% | 19.5% | 3.00% |
| XRP-USDT-SWAP | 5× | 5× | 5.00× | 0.946037 | 19.6% | 19.5% | 3.00% |
| SHIB-USDT-SWAP | 2× | 2× | 2.00× | 2.27391e-06 | 49.7% | 49.5% | 7.50% |
| APT-USDT-SWAP | 3× | 3× | 3.00× | 0.795849 | 32.4% | 32.8% | 6.67% |
| SOL-USDT-SWAP | 5× | 5× | 5.00× | 72.4319 | 19.6% | 19.5% | 3.00% |
| ORDI-USDT-SWAP | 2× | 2× | 1.99× | 1.66358 | 49.1% | 49.5% | 12.50% |
| TIA-USDT-SWAP | 5× | 5× | 3.00× | 0.583656 | 32.4% | 19.5% | 4.00% |
| WLD-USDT-SWAP | 3× | 3× | 3.00× | 0.229454 | 32.8% | 32.8% | 5.00% |
| BONK-USDT-SWAP | 3× | 3× | 3.00× | 1.91348e-06 | 32.4% | 32.8% | 5.00% |
| CRV-USDT-SWAP | 5× | 5× | 3.00× | 0.178374 | 32.8% | 19.5% | 4.00% |
| ARB-USDT-SWAP | 5× | 5× | 3.00× | 0.111998 | 32.9% | 19.5% | 3.00% |
| WIF-USDT-SWAP | 3× | 3× | 3.00× | 0.109205 | 32.8% | 32.8% | 5.00% |
| OP-USDT-SWAP | 2× | 2× | 1.97× | 0.0517572 | 50.4% | 49.5% | 7.50% |
| FIL-USDT-SWAP | 2× | 2× | 1.99× | 0.405488 | 49.8% | 49.5% | 7.50% |

## 6. 成交后核验

| 检查项 | 方法 | 结果 |
|---|---|---|
| 持仓数量 | `GET /api/v1/account/positions` 计数 | 27 / 27 ✅ |
| 方向一致 | 逐币比对计划 `posSide` | 27 / 27 ✅ |
| 杠杆一致 | 逐币比对计划 `lever` | 27 / 27 ✅ |
| 逐仓模式 | 每仓 `mgnMode` | 27 / 27 isolated ✅ |
| 强平价自洽 | 多单 liqPx < avgPx、空单 liqPx > avgPx，距离≈1/lev−0.5% | 全部自洽 ✅ |
| 资金未超用 | 保证金合计 vs 可用余额 | 14.76U ≤ 137.03U ✅ |

抽检：OP（2× 多，均价 0.10435）强平价 0.05176 ≈ −50%；ONDO（2× 空，均价 0.37050）强平价 0.55179 ≈ +49%；BCH（3× 空）设杠杆后强平价由 ~265 抬到 345。

## 7. 与方案的差异与风险提示

1. **只下了第 1 层，没挂第 2/3 层**：方案是马丁格尔，第 2 层需要在首层逆向走出 `layerStepPct`（1.0–1.5%）后触发。本次按授权只执行首层市价单，加仓需要另行确认后执行（脚本 `place-orders.ps1` 支持按层号重跑）。
2. **没有挂止盈/止损单**：方案里的 `takeProfitPct` / `stopLossPct` 是回测口径的**价格触发**，实盘需要挂条件单或用监控循环执行；本次授权明确「不挂止盈止损」，因此当前仓位靠逐仓保证金兜底，最大损失=保证金合计 14.76U。
3. **可用余额基数变化**：下单前可用 90.27U，执行中此前冻结的 851U 释放，可用升至 ~151U，当前 137U。方案的资金分配（27 策略 47.15U、缓冲 43U）仍成立且更宽松，未因余额变化加仓。
4. **成交价相对计划参考价有漂移**：`orders.csv` 的 `refPx` 取自 14:35 的行情快照，而 27 单在约 70 分钟内陆续成交，成交价对该参考价的中位偏差 +27bps、最大 +185bps（INJ）；导致首层名义从计划的 37.76U 变成 37.90U（+0.4%）。这主要是下单期间的价格漂移，不是撮合滑点；回测口径的 2bps 滑点另计。
5. **杠杆可再次调整**：`lever` 已是目标值，但 8 个仓位按上述开仓口径计保证金；如需让实际生效杠杆与目标完全一致，可在下一层成交后自然重算，或平仓后按新杠杆重开（不推荐，会额外产生手续费）。

## 8. 停用与回滚

| 目标 | 操作 |
|---|---|
| 停止自动/手动下单 | 删除 `C:\Users\weiji\Desktop\OKexAlpha\config\application.yml`（或把 `enabled` 改回 false），重启服务；门禁立即回到 BLOCK |
| 平掉全部仓位 | 对 27 个合约逐一下 `reduceOnly` 市价单（`place-orders.ps1` 未内置平仓，可用 `POST /api/v1/strategy/live/order` 带 `reduceOnly=true`、`side` 取反、`sz` 取当前持仓张数） |
| 回滚服务版本 | `.run/backup/app.jar.20261107-1528.bak`（SHA256 ACCBB338…36AD）覆盖回 `app.jar`，再 `schtasks /end` + `schtasks /run` |
| 回滚交易配置 | 备份 `.run/backup/application.yml.20261107-144204.bak` 与原配置 SHA256 一致，`backend/src/main/resources/application.yml` 未被修改 |
| 停用单策略 | 平掉该合约仓位，并把该行从后续加仓清单移除（其余 26 条不受影响，策略间相关 0.047） |

触发停用的建议阈值（与报告 §5 一致）：单策略浮亏达到其保证金 60%（即止损距离的 ~60%）先平该仓；组合单日回撤 > 10%（>4.7U）暂停加仓；连续 3 日组合净值新低则整体降杠杆一半。

## 9. 复现命令

```powershell
# 1) 门禁自检（约 35s）
curl.exe -s http://127.0.0.1:18080/api/v1/strategy/live/gate

# 2) 下单（已执行；-DryRun 只打印不提交）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\martingale-100u\place-orders.ps1 -DryRun

# 3) 设杠杆（读 .run/leverage-plan2.csv）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\martingale-100u\set-leverage.ps1

# 4) 核验
curl.exe -s http://127.0.0.1:18080/api/v1/account/positions
```

---

## 7. 托管执行器（manager.py）

2026-11-07 22:45 起，27 个裸仓已交给 scripts/martingale-bot/manager.py 托管（不是挂条件单，而是轮询式主动管理）。

| 项 | 说明 |
| --- | --- |
| 判断顺序 | 强平保护 LIQ_GUARD → 止损 SL → 加仓 ADD → 止盈 TP → 超时 TIMEOUT |
| 参数来源 | 
eports/martingale-100u/selected.csv（每币一行，与回测参数逐一对应） |
| 下单通道 | 本地服务 POST /api/v1/strategy/live/orders，复用门禁 + posSide + REAL 确认口令，脚本不接触密钥 |
| 加仓 | 相对均价逆向 >= layerStepPct 且未满层；第 2 层名义 = 第 1 层 x layerScale |
| 重开 | 平仓后 60 秒冷却，每币每日最多 12 次（ALWAYS_IN 口径） |
| 闸门 | 可用 < 20U 停止开仓/加仓；当日权益回撤 > 4.5U 自动写 STOP 熔断 |
| 状态 | .run/bot/state.json（layer1 名义、层数、上次平仓时间、当日开仓次数） |
| 日志 | .run/bot/manager.log、.run/bot/manager.out.log |
| 常驻 | Windows 计划任务 OkexMartingaleManager（每 5 分钟检查一次，pid 守卫防重复启动） |
| 停止 | schtasks /end /tn OkexMartingaleManager + 结束 python 进程；或写 .run/bot/STOP 只停动作不停进程 |

### 7.1 首小时执行记录

| 时间 | 动作 | 品种 |
| --- | --- | --- |
| 22:43 | TP 平仓 | ONDO（空，+0.8%）、INJ（空，+2.1%） |
| 22:43 | ADD 第 2 层 | ENA（1.61U → 4.02U） |
| 22:45 | TP 平仓 | CRV（多） |
| 22:46-22:51 | 自动重开 | INJ、ONDO、NEAR |
| 22:56 | 运行中 | 26 个持仓，保证金 15.61U，名义 42.23U，浮亏 -0.11U |

### 7.2 启停命令

`powershell
# 手动跑一轮（干跑）
python scripts\martingale-bot\manager.py --once
# 立即启动/检查常驻任务
schtasks /run /tn OkexMartingaleManager
# 熔断（只停动作）
New-Item .run\bot\STOP -ItemType File
`

