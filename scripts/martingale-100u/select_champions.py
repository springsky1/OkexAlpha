#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Step 2: champion-level selection with equity curves."""
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
PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
ENV = dict(os.environ, PGPASSWORD="123456")


def q(sql):
    p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                        "-c", "copy (" + sql + ") to stdout with (format csv, header true)"],
                       capture_output=True, env=ENV)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return pd.read_csv(io.BytesIO(p.stdout), encoding="utf-8")


CHAMP_SQL = """
select run.id run_id, run.label, a.id all_result_id, a.param_key, a.params::text params,
       a.trade_count is_trades, a.win_rate is_wr, a.net_pnl_pct is_ret, a.max_drawdown_pct is_dd,
       a.oos_net_pnl_pct oos_ret,
       (a.metrics->'outOfSample'->>'tradeCount')::int oos_trades,
       (a.metrics->'outOfSample'->>'maxDrawdownPct')::float8 oos_dd,
       (a.metrics->>'score')::float8 score, a.verdict, a.liquidation_count is_liq,
       c.id coin_result_id, c.inst_id,
       (c.metrics->'metrics'->>'trades')::int m_trades,
       (c.metrics->'metrics'->>'winRate')::float8 m_wr,
       (c.metrics->'metrics'->>'netPnlUsd')::float8 m_pnl,
       (c.metrics->'metrics'->>'returnPct')::float8 m_ret,
       (c.metrics->'metrics'->>'maxDrawdownPct')::float8 m_dd,
       (c.metrics->'metrics'->>'sharpe')::float8 m_sharpe,
       (c.metrics->'metrics'->>'calmar')::float8 m_calmar,
       (c.metrics->'metrics'->>'liquidations')::int m_liq,
       (c.metrics->'metrics'->>'worstTradePct')::float8 m_worst,
       (c.metrics->'metrics'->>'profitFactor')::float8 m_pf,
       (c.metrics->'metrics'->>'avgHoldHours')::float8 m_hold
from okx_backtest_result a
join okx_backtest_run run on run.id = a.run_id
join okx_backtest_result c on c.run_id = a.run_id and c.segment = 'COIN' and c.param_key = a.param_key
where run.label like 'M100-%' and run.label not like '%test%'
  and a.segment = 'ALL' and a.rank = 1
"""

EQUITY_SQL = """
select result_id, ts_ms, equity, drawdown_pct from okx_backtest_equity
where result_id in (select c.id from okx_backtest_result c join okx_backtest_run run on run.id=c.run_id
                    where run.label like 'M100-%' and run.label not like '%test%' and c.segment='COIN')
order by result_id, ts_ms
"""


def main():
    ch = q(CHAMP_SQL)
    pj = pd.json_normalize(ch["params"].map(json.loads))
    ch = ch.drop(columns=["params"]).join(pj)
    ch["S"] = [sum(s ** i for i in range(int(ml))) for ml, s in zip(ch.maxLayers, ch.layerScale)]
    ch["capital_min"] = ch.initialMarginUsd * ch.S
    ch.to_csv(os.path.join(OUT, "champions.csv"), index=False)
    print("champions", len(ch), "coins", ch.inst_id.nunique())

    eq = q(EQUITY_SQL)
    eq.to_csv(os.path.join(OUT, "equity_raw.csv"), index=False)
    print("equity rows", len(eq), "series", eq.result_id.nunique(),
          "points/series", eq.groupby("result_id").size().describe()[["min", "50%", "max"]].to_dict())

    for name, mask in {
        "T1 verdict=CANDIDATE": ch.verdict == "CANDIDATE",
        "T1+liq0+tr30+pnl>0": (ch.verdict == "CANDIDATE") & (ch.m_liq == 0) & (ch.m_trades >= 30) & (ch.m_pnl > 0),
        "T2 no-verdict": (ch.m_liq == 0) & (ch.m_trades >= 100) & (ch.m_pnl > 0) & (ch.oos_dd < 25) & (ch.m_dd < 120),
    }.items():
        s = ch[mask]
        print(f"{name}: rows={len(s)} coins={s.inst_id.nunique()}")

    ok = ch[(ch.verdict == "CANDIDATE") & (ch.m_liq == 0) & (ch.m_trades >= 30) & (ch.m_pnl > 0)]
    b = ok.sort_values("score", ascending=False).groupby("inst_id", as_index=False).first()
    cols = ["inst_id", "leverage", "side", "maxLayers", "layerStepPct", "layerScale", "takeProfitPct",
            "stopLossPct", "score", "m_ret", "m_dd", "m_pnl", "m_trades", "m_wr", "capital_min", "S"]
    print(b[cols].sort_values("score", ascending=False).to_string(index=False))
    print("sum capital_min", round(b.capital_min.sum(), 3))


if __name__ == "__main__":
    main()
