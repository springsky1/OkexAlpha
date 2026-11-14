#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Final selection + portfolio + deliverables for the 100U martingale study.

Inputs : okx_backtest_run / _result / _equity / _trade, okx_kline (gap risk), config json.
Outputs: reports/martingale-100u/{specs.csv,selected.csv,ladder.csv,orders.csv,portfolio.csv,
         risk.csv,excluded.csv,summary.json}
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
LABELS = "run.label like 'M10%' and run.label not like '%test%'"
BUDGET_DEPLOY = 72.22   # 80% of 90.27123346585422
BUDGET_HARD = 90.27123346585422


def q(sql):
    p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha", "-c",
                        "copy (" + sql + ") to stdout with (format csv, header true)"],
                       capture_output=True, env=ENV)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return pd.read_csv(io.BytesIO(p.stdout), encoding="utf-8")


CHAMPS = f"""
select run.id run_id, run.label, a.id all_id, a.param_key, a.params::text params,
       a.trade_count is_trades, a.win_rate is_wr, a.net_pnl_pct is_ret, a.max_drawdown_pct is_dd,
       a.oos_net_pnl_pct oos_ret,
       (a.metrics->'outOfSample'->>'trades')::int oos_trades,
       (a.metrics->'outOfSample'->>'maxDrawdownPct')::float8 oos_dd,
       (a.metrics->>'score')::float8 score, a.verdict,
       c.id coin_id, c.inst_id,
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
       (c.metrics->'metrics'->>'avgHoldHours')::float8 m_hold,
       (select count(*) from okx_backtest_equity e where e.result_id = c.id) eq_pts
from okx_backtest_result a
join okx_backtest_run run on run.id = a.run_id
join okx_backtest_result c on c.run_id = a.run_id and c.segment = 'COIN' and c.param_key = a.param_key
where {LABELS} and a.segment = 'ALL' and a.rank = 1
"""

COINS = f"""
select run.id run_id, run.label, a.id all_id, a.param_key, a.params::text params,
       a.verdict, (a.metrics->>'score')::float8 score, a.oos_net_pnl_pct oos_ret,
       (a.metrics->'outOfSample'->>'trades')::int oos_trades,
       (a.metrics->'outOfSample'->>'maxDrawdownPct')::float8 oos_dd,
       c.id coin_id, c.inst_id,
       (c.metrics->'metrics'->>'trades')::int m_trades,
       (c.metrics->'metrics'->>'winRate')::float8 m_wr,
       (c.metrics->'metrics'->>'netPnlUsd')::float8 m_pnl,
       (c.metrics->'metrics'->>'returnPct')::float8 m_ret,
       (c.metrics->'metrics'->>'maxDrawdownPct')::float8 m_dd,
       (c.metrics->'metrics'->>'liquidations')::int m_liq,
       (c.metrics->'metrics'->>'worstTradePct')::float8 m_worst,
       (c.metrics->'metrics'->>'profitFactor')::float8 m_pf
from okx_backtest_result a
join okx_backtest_run run on run.id = a.run_id
join okx_backtest_result c on c.run_id = a.run_id and c.segment = 'COIN' and c.param_key = a.param_key
where {LABELS} and a.segment = 'ALL' and a.rank = 1
"""

TRADES = f"""
select t.result_id, count(*) n_tr, max(t.layers) max_layers,
       max(t.notional_usd) max_notional, avg(t.notional_usd) avg_notional
from okx_backtest_trade t join okx_backtest_run run on run.id = t.run_id
where {LABELS} group by 1
"""

EQUITY = f"""
select e.result_id, e.ts_ms, e.equity
from okx_backtest_equity e
join okx_backtest_result c on c.id = e.result_id
join okx_backtest_run run on run.id = c.run_id
where {LABELS}
order by e.result_id, e.ts_ms
"""


def explode(df, col="params"):
    pj = pd.json_normalize(df[col].map(json.loads))
    return df.drop(columns=[col]).join(pj)


def with_capital(ch):
    ch = ch.copy()
    ch["S"] = [sum(s ** i for i in range(int(ml))) for ml, s in zip(ch.maxLayers, ch.layerScale)]
    ch["capital_min"] = ch.initialMarginUsd * ch.S
    ch["liq_dist_pct"] = (1.0 / ch.leverage - 0.005) * 100          # price move to liquidation
    ch["stop_dist_pct"] = ch.stopLossPct / 100.0 / ch.leverage * 100  # price move to stop
    return ch


def main():
    ch = with_capital(explode(q(CHAMPS)))
    allc = with_capital(explode(q(COINS)))
    tr = q(TRADES)
    eq = q(EQUITY)
    ch = ch.merge(tr, left_on="coin_id", right_on="result_id", how="left")
    ch.to_csv(os.path.join(OUT, "champions_all.csv"), index=False)
    print("champions", len(ch), "coins", ch.inst_id.nunique(), "with equity", int((ch.eq_pts > 0).sum()))

    # ---------------- filters
    strict = ((ch.verdict == "CANDIDATE") & (ch.m_liq == 0) & (ch.m_trades >= 100) &
              (ch.m_pnl > 0) & (ch.m_dd <= 110) & (ch.oos_trades >= 30) & (ch.oos_dd < 25) &
              (ch.eq_pts > 200))
    ch["tier"] = np.where(strict, "T1", "")
    print("T1 champions:", int(strict.sum()), "coins", ch[strict].inst_id.nunique())

    sel = (ch[strict].sort_values(["score", "m_pnl"], ascending=False)
           .groupby("inst_id", as_index=False).first())
    # fallback tier for coins without T1 (relaxed but still liq=0, positive full-window)
    relax = (~strict & (ch.m_liq == 0) & (ch.m_trades >= 100) & (ch.m_pnl > 0) &
             (ch.m_dd <= 120) & (ch.oos_trades >= 30) & (ch.oos_dd < 35) & (ch.eq_pts > 200))
    fb = (ch[relax].sort_values("score", ascending=False)
          .groupby("inst_id", as_index=False).first())
    fb = fb[~fb.inst_id.isin(sel.inst_id)].copy()
    fb["tier"] = "T2"
    print("T2 fallback coins:", len(fb), sorted(fb.inst_id.str.split("-").str[0]))

    sel["tier"] = "T1"
    pool = pd.concat([sel, fb], ignore_index=True).sort_values("score", ascending=False)
    pool.to_csv(os.path.join(OUT, "selected_pool.csv"), index=False)
    print("pool", len(pool), "capital_min sum", round(pool.capital_min.sum(), 4))

    # excluded coins summary
    ex = ch[~ch.inst_id.isin(pool.inst_id)].sort_values("score", ascending=False).groupby("inst_id", as_index=False).first()
    ex[["inst_id", "leverage", "side", "score", "verdict", "m_ret", "m_dd", "m_pnl", "m_liq",
        "oos_ret", "oos_dd"]].to_csv(os.path.join(OUT, "excluded.csv"), index=False)

    # ---------------- allocation (Plan A: minimum capital)
    pool["alloc_usd"] = pool.capital_min.round(2)
    tot = pool.alloc_usd.sum()
    print("PlanA total", round(tot, 2), "util", round(tot / BUDGET_HARD * 100, 1), "%")

    # ---------------- portfolio equity
    piv = eq.pivot(index="ts_ms", columns="result_id", values="equity").sort_index()
    piv = piv.ffill()
    cols = {}
    for _, r in pool.iterrows():
        if r.coin_id in piv.columns:
            cols[r.inst_id] = (piv[r.coin_id] - r.initialMarginUsd).ffill().fillna(0)
    pnl = pd.DataFrame(cols)
    pnl.index = pd.to_datetime(pnl.index, unit="ms", utc=True).tz_convert("Asia/Shanghai")
    port = pnl.sum(axis=1)
    cap = pool.alloc_usd.sum()
    curve = pd.DataFrame({"pnl_usd": port, "equity": cap + port, "dd_pct": (cap + port) / (cap + port).cummax() - 1})
    curve.to_csv(os.path.join(OUT, "portfolio_curve.csv"))
    ret_daily = port.diff().fillna(port.iloc[0]) / cap
    stats = {
        "days": int(len(port)), "capital": round(cap, 2),
        "total_pnl": round(port.iloc[-1], 2),
        "total_return_pct": round(port.iloc[-1] / cap * 100, 2),
        "max_dd_pct": round(float(curve.dd_pct.min()) * 100, 2),
        "sharpe": round(float(ret_daily.mean() / ret_daily.std() * np.sqrt(365)), 2) if ret_daily.std() > 0 else None,
        "worst_day_pct": round(float(ret_daily.min()) * 100, 2),
        "corr_mean": round(float(pnl.pct_change().dropna().corr().values[np.triu_indices(len(pnl.columns), 1)].mean()), 3),
        "strategies": int(len(pool)),
    }
    print("portfolio stats", json.dumps(stats, ensure_ascii=False))

    # per-strategy monthly contribution
    monthly = pnl.resample("ME").sum()
    pool = pool.merge(
        pd.DataFrame({"inst_id": [c.split("-")[0] for c in pnl.columns],
                      "pnl_per_month": (pnl.sum() / len(monthly)).values}),
        on=None, how="left") if False else pool

    summary = {"budget": {"avail": BUDGET_HARD, "deploy": BUDGET_DEPLOY, "used": round(cap, 2),
                          "utilization_pct": round(cap / BUDGET_HARD * 100, 2),
                          "buffer": round(BUDGET_HARD - cap, 2)},
               "portfolio": stats,
               "months": len(monthly)}
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2, default=float)
    print(json.dumps(summary, ensure_ascii=False, indent=2, default=float))


if __name__ == "__main__":
    main()
