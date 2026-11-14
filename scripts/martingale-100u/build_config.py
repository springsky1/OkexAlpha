#!/usr/bin/env python3
"""生成 100U 马丁格尔最小开单回测的配置快照（config/martingale-100u.json）。

数据来源：本机 PostgreSQL(okex_alpha) 的 okx_min_order / okx_instrument / okx_funding_rate / okx_kline。
本文件是回测输入的唯一真源：窗口、成本、网格、候选币与逐币最小下单规格都在这里，
run-grid.ps1 与 analyze.py 都按同一套口径读它。
"""
import json
import os
import subprocess
from datetime import datetime, timezone, timedelta

PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
DB = "okex_alpha"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "config", "martingale-100u.json")

UNIVERSE = [
    "BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP", "XRP-USDT-SWAP", "DOGE-USDT-SWAP",
    "ADA-USDT-SWAP", "LINK-USDT-SWAP", "AVAX-USDT-SWAP", "TRX-USDT-SWAP", "DOT-USDT-SWAP",
    "LTC-USDT-SWAP", "BCH-USDT-SWAP", "SUI-USDT-SWAP", "NEAR-USDT-SWAP", "APT-USDT-SWAP",
    "ETC-USDT-SWAP", "FIL-USDT-SWAP", "ATOM-USDT-SWAP", "UNI-USDT-SWAP", "AAVE-USDT-SWAP",
    "ARB-USDT-SWAP", "OP-USDT-SWAP", "TIA-USDT-SWAP", "INJ-USDT-SWAP", "CRV-USDT-SWAP",
    "WLD-USDT-SWAP", "ORDI-USDT-SWAP", "SEI-USDT-SWAP", "JUP-USDT-SWAP", "ONDO-USDT-SWAP",
    "ENA-USDT-SWAP", "PEPE-USDT-SWAP", "SHIB-USDT-SWAP", "WIF-USDT-SWAP", "BONK-USDT-SWAP",
]


def psql(sql: str) -> list[list[str]]:
    env = dict(os.environ, PGPASSWORD="123456")
    p = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", DB, "-t", "-A", "-F", "\t",
         "-v", "ON_ERROR_STOP=1", "-c", "set statement_timeout='120s'; " + sql],
        capture_output=True, text=True, encoding="utf-8", env=env,
    )
    if p.returncode != 0:
        raise RuntimeError(p.stderr)
    return [line.split("\t") for line in p.stdout.splitlines() if line and line != "SET"]


def main() -> None:
    ids = ",".join("'%s'" % i for i in UNIVERSE)

    spec_rows = psql(f"""
        select m.inst_id, m.ct_val, m.ct_val_ccy, m.lot_sz, m.min_sz, m.tick_sz, m.ref_px,
               m.notional_per_contract_usd, m.min_notional_usd, i.lever, m.refreshed_at
        from okx_min_order m join okx_instrument i on i.inst_id = m.inst_id
        where m.inst_id in ({ids}) and m.tradable order by m.min_notional_usd
    """)
    fund_rows = psql(f"""
        select inst_id, avg(funding_rate), count(*), min(funding_time_ms), max(funding_time_ms)
        from okx_funding_rate where inst_id in ({ids}) group by inst_id
    """)
    fund = {r[0]: {"avg8h": float(r[1]), "points": int(r[2]),
                   "fromMs": int(r[3]), "toMs": int(r[4])} for r in fund_rows}
    kline_rows = psql(f"""
        select inst_id, count(*), min(ts_ms), max(ts_ms) from okx_kline
        where bar='1H' and kline_type='CANDLE' and inst_id in ({ids}) group by inst_id
    """)
    kl = {r[0]: {"bars": int(r[1]), "firstMs": int(r[2]), "lastMs": int(r[3])} for r in kline_rows}

    coins = []
    for r in spec_rows:
        inst = r[0]
        coins.append({
            "instId": inst,
            "ctVal": float(r[1]),
            "ctValCcy": r[2],
            "lotSz": float(r[3]),
            "minSz": float(r[4]),
            "tickSz": float(r[5]),
            "refPx": float(r[6]),
            "notionalPerContractUsd": float(r[7]),
            "minNotionalUsd": float(r[8]),
            "maxLeverage": int(float(r[9])),
            "specRefreshedAt": r[10],
            "funding": fund.get(inst, {}),
            "kline1H": kl.get(inst, {}),
        })

    budget_available = 90.27123346585422
    cfg = {
        "name": "martingale-100u-min-notional-grid",
        "generatedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "account": {
            "ccy": "USDT",
            "mode": "SHADOW",
            "credentialSource": "db",
            "acctLv": 2,
            "posMode": "long_short_mode",
            "perm": ["read_only", "trade"],
            "totalEqUsd": 947.1108926214816,
            "availBalUsd": budget_available,
            "frozenBalUsd": 851.2623014328219,
            "isoEqUsd": 6.7948199784,
            "snapshotAt": "2026-11-07T14:30+08:00",
        },
        "budget": {
            "targetUsd": 100.0,
            "availableUsd": budget_available,
            "reservePct": 20.0,
            "deployableUsd": round(budget_available * 0.8, 2),
            "hardCapUsd": budget_available,
        },
        "window": {
            "bar": "1H",
            "startMs": 1767196800000,
            "endMs": 1794031200000,
            "startLocal": "2026-01-01T00:00:00+08:00",
            "endLocal": "2026-11-07T23:00:00+08:00",
            "note": "endMs 为右开区间；1H 数据末端 2026-11-07 23:00(+08)",
        },
        "costs": {
            "feeTaker": 0.0005,
            "slippageBps": 2.0,
            "fundingPer8hPct": 0.01,
            "maxHoldHours": 48,
            "maintenanceMarginRate": 0.005,
        },
        "engine": {
            "combos": 216,
            "topN": 20,
            "seed": 20261107,
            "coinLimit": 1,
            "minTrades": 10,
            "minOosTrades": 30,
            "maxOosDrawdownPct": 25.0,
            "inSampleRatio": 0.7,
        },
        "ranges": {
            "leverage": {"min": 3, "max": 10, "step": 2, "note": "按批次固定为 3/5/10 之一"},
            "maxLayers": {"min": 3, "max": 8, "step": 2},
            "layerStepPct": {"min": 1.5, "max": 4.5, "step": 1.5},
            "layerScale": {"min": 1.0, "max": 2.0, "step": 0.5},
            "takeProfitPct": {"min": 1.0, "max": 3.0, "step": 1.0},
            "stopLossPct": {"min": 15.0, "max": 25.0, "step": 10.0},
            "sides": ["long", "short"],
        },
        "marginRule": {
            "initialMarginUsd": "minNotionalUsd / leverage  (第 1 层名义 = 平台最小下单额)",
            "strategyMaxMarginUsd": "initialMarginUsd * sum(layerScale^(i-1), i=1..maxLayers)",
        },
        "coins": coins,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("wrote", OUT, "coins:", len(coins))


if __name__ == "__main__":
    main()
