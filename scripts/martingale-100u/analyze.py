#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Martingale-100U grid analysis: pick strategies, build portfolio, emit tables.

Data: okx_backtest_result (segment ALL = IS/OOS aggregate; COIN = full-window per coin)
      joined to okx_backtest_run by label LIKE 'M100-%'.
Output: reports/martingale-100u/*.csv + analysis.json (consumed by report generator).
"""
import io
import json
import os
import subprocess
import sys

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "reports", "martingale-100u")
os.makedirs(OUT, exist_ok=True)
PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
ENV = dict(os.environ, PGPASSWORD="123456")

with open(os.path.join(ROOT, "config", "martingale-100u.json"), encoding="utf-8") as f:
    CFG = json.load(f)


def q(sql: str) -> pd.DataFrame:
    p = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha", "-v", "ON_ERROR_STOP=1",
         "-c", "copy (" + sql + ") to stdout with (format csv, header true)"],
        capture_output=True, env=ENV, check=True)
    return pd.read_csv(io.BytesIO(p.stdout), encoding="utf-8")


ALL_SQL = """
select run.id run_id, run.label, r.id result_id, r.param_key, r.params::text params,
       r.trade_count, r.win_rate, r.net_pnl, r.net_pnl_pct, r.max_drawdown_pct, r.sharpe,
       r.liquidation_count, r.oos_net_pnl_pct, r.verdict, r.rank,
       (r.metrics->>'score')::float8 score,
       (r.metrics->'outOfSample'->>'tradeCount')::int oos_trades,
       (r.metrics->'outOfSample'->>'maxDrawdownPct')::float8 oos_dd,
       (r.metrics->'outOfSample'->>'netPnlUsd')::float8 oos_pnl_usd
from okx_backtest_result r join okx_backtest_run run on run.id = r.run_id
where run.label like 'M100-%' and r.segment = 'ALL'"""

COIN_SQL = """
select run.id run_id, r.id result_id, r.param_key, r.inst_id, r.params::text params,
       r.trade_count, r.win_rate, r.net_pnl, r.net_pnl_pct, r.max_drawdown_pct, r.sharpe,
       r.liquidation_count,
       (r.metrics->'metrics'->>'trades')::int m_trades,
       (r.metrics->'metrics'->>'liquidations')::int m_liq,
       (r.metrics->'metrics'->>'netPnlUsd')::float8 m_pnl,
       (r.metrics->'metrics'->>'returnPct')::float8 m_ret,
       (r.metrics->'metrics'->>'maxDrawdownPct')::float8 m_dd,
       (r.metrics->'metrics'->>'worstTradePct')::float8 m_worst,
       (r.metrics->'metrics'->>'profitFactor')::float8 m_pf,
       (r.metrics->'metrics'->>'calmar')::float8 m_calmar,
       (r.metrics->'metrics'->>'maxConsecutiveLosses')::int m_mcl
from okx_backtest_result r join okx_backtest_run run on run.id = r.run_id
where run.label like 'M100-%' and r.segment = 'COIN'"""


def explode(df: pd.DataFrame) -> pd.DataFrame:
    pj = pd.json_normalize(df["params"].map(json.loads))
    df = df.drop(columns=["params"]).join(pj)
    return df


def main() -> None:
    all_rows = explode(q(ALL_SQL))
    coin_rows = explode(q(COIN_SQL))
    all_rows.to_csv(os.path.join(OUT, "grid_all_raw.csv"), index=False)
    coin_rows.to_csv(os.path.join(OUT, "grid_coin_raw.csv"), index=False)
    print("ALL rows", len(all_rows), "COIN rows", len(coin_rows))
    print(all_rows.columns.tolist())
    # merge full-window metric per (run_id, param_key)
    keep = ["run_id", "param_key", "result_id", "m_trades", "m_liq", "m_pnl", "m_ret", "m_dd",
            "m_worst", "m_pf", "m_calmar", "m_mcl", "inst_id"]
    merged = all_rows.merge(coin_rows[keep], on=["run_id", "param_key"], how="left",
                            suffixes=("", "_coin"))
    print("merged with full-window:", int(merged["m_pnl"].notna().sum()))
    merged.to_csv(os.path.join(OUT, "grid_merged.csv"), index=False)

    # ---- overview of the merged universe
    m = merged.dropna(subset=["m_pnl"])
    print("\n== candidates with full-window data ==")
    print("total", len(m), "CANDIDATE", int((m.verdict == "CANDIDATE").sum()),
          "liq0", int((m.m_liq == 0).sum()))
    ok = m[(m.verdict == "CANDIDATE") & (m.m_liq == 0) & (m.m_trades >= 30) & (m.m_pnl > 0)]
    print("strict pass", len(ok), "coins", ok.inst_id.nunique(), sorted(ok.inst_id.unique()))
    print("\nper coin best (strict):")
    best = ok.sort_values("score", ascending=False).groupby("inst_id", as_index=False).first()
    print(best[["inst_id", "leverage", "side", "maxLayers", "layerStepPct", "layerScale",
                "takeProfitPct", "stopLossPct", "score", "m_ret", "m_dd", "m_pnl", "m_trades",
                "win_rate"]].sort_values("score", ascending=False).to_string(index=False))


if __name__ == "__main__":
    main()
