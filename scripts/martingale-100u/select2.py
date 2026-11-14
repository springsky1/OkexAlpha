#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Wave-3 selection: M200 champions -> per-coin best -> budget portfolio -> selected2.csv."""
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

AVAIL = 90.27123346585422
DEPLOY = 72.22
CAP_PER = AVAIL * 0.10      # 9.02U single strategy capital
CAP_LOSS = AVAIL * 0.02     # 1.81U single-trade worst loss


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
       (a.metrics->'outOfSample'->>'trades')::int oos_trades2,
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
where run.label like 'M200-%' and run.label not like '%test%'
  and a.segment = 'ALL' and a.rank = 1
"""

SPEC_SQL = """
select inst_id, ct_val, lot_sz, min_sz, ref_px, notional_per_contract_usd, min_notional_usd, refreshed_at
from okx_min_order
"""


def main():
    ch = q(CHAMP_SQL)
    if ch.empty:
        print("no champions yet")
        return 1
    pj = pd.json_normalize(ch["params"].map(json.loads))
    ch = ch.drop(columns=["params"]).join(pj)
    ch["S"] = [sum(s ** i for i in range(int(ml))) for ml, s in zip(ch.maxLayers, ch.layerScale)]
    ch["capital_min"] = ch.initialMarginUsd * ch.S
    ch["worst_loss"] = ch.capital_min * ch.stopLossPct / 100.0
    ch["roi_cap"] = ch.m_pnl / ch.capital_min
    ch["oos_trades"] = ch["oos_trades"].fillna(ch["oos_trades2"])
    ch.to_csv(os.path.join(OUT, "champions2.csv"), index=False)
    print("champions", len(ch), "coins", ch.inst_id.nunique())

    base = (ch.m_liq == 0) & (ch.m_trades >= 100) & (ch.m_pnl > 0) & (ch.m_dd <= 110) \
        & (ch.oos_dd < 25) & (ch.oos_ret > 0) & (ch.score > 0) & (ch.worst_loss > 0)
    print("pass base", int(base.sum()), "coins", ch[base].inst_id.nunique())

    capped = base & (ch.capital_min <= CAP_PER) & (ch.worst_loss <= CAP_LOSS)
    print("pass caps", int(capped.sum()), "coins", ch[capped].inst_id.nunique())

    pool = ch[capped].copy()
    pool["rank_metric"] = pool.roi_cap
    best = pool.sort_values("rank_metric", ascending=False).groupby("inst_id", as_index=False).first()
    best = best.sort_values("rank_metric", ascending=False)

    # budget: greedy by rank_metric while sum(capital) <= DEPLOY
    sel, total = [], 0.0
    for _, r in best.iterrows():
        if total + r.capital_min <= DEPLOY:
            sel.append(r)
            total += r.capital_min
    S = pd.DataFrame(sel)
    S["layer1_notional_usd"] = S["minNotionalUsd"] if "minNotionalUsd" in S else np.nan
    print("selected", len(S), "capital", round(total, 4), "coins dropped by budget",
          len(best) - len(S))

    spec = q(SPEC_SQL).set_index("inst_id")
    rows = []
    for _, r in S.iterrows():
        sp = spec.loc[r.inst_id] if r.inst_id in spec.index else None
        rows.append({
            "inst_id": r.inst_id, "side": r.side, "leverage": int(r.leverage),
            "maxLayers": int(r.maxLayers), "layerScale": float(r.layerScale),
            "layerStepPct": float(r.layerStepPct), "takeProfitPct": float(r.takeProfitPct),
            "stopLossPct": float(r.stopLossPct), "capital_usd": round(float(r.capital_min), 6),
            "layer1_notional_usd": round(float(sp.min_notional_usd), 6) if sp is not None else np.nan,
            "worst_loss_usd": round(float(r.worst_loss), 6),
            "score": round(float(r.score), 4), "m_pnl": round(float(r.m_pnl), 4),
            "m_ret": round(float(r.m_ret), 4), "m_dd": round(float(r.m_dd), 4),
            "m_trades": int(r.m_trades), "m_wr": round(float(r.m_wr), 4),
            "m_sharpe": round(float(r.m_sharpe), 4), "coin_result_id": int(r.coin_result_id),
        })
    sel_df = pd.DataFrame(rows)
    sel_df.to_csv(os.path.join(OUT, "selected2.csv"), index=False, encoding="utf-8-sig")

    spec_rows = []
    for inst in sel_df.inst_id:
        if inst not in spec.index:
            continue
        sp = spec.loc[inst]
        spec_rows.append({"inst_id": inst, "ctVal": sp.ct_val, "lotSz": sp.lot_sz, "minSz": sp.min_sz,
                          "refPx": sp.ref_px, "minNotionalUsd": sp.min_notional_usd,
                          "maxLeverage": None, "refreshed_at": sp.refreshed_at})
    pd.DataFrame(spec_rows).to_csv(os.path.join(OUT, "specs2.csv"), index=False, encoding="utf-8-sig")

    # portfolio curve: daily last equity per selected result, ffill on common axis, sum deltas
    ids = ",".join(str(int(x)) for x in sel_df.coin_result_id)
    eq = q(f"select result_id, ts_ms, equity from okx_backtest_equity where result_id in ({ids}) order by ts_ms")
    eq["date"] = pd.to_datetime(eq.ts_ms, unit="ms", utc=True).dt.tz_convert("Asia/Shanghai").dt.date
    piv = eq.groupby(["date", "result_id"]).equity.last().unstack().sort_index().ffill()
    piv = piv.dropna(how="all")
    common = piv.dropna()
    deltas = piv.sub(piv.bfill().iloc[0], axis=1)
    pcurve = deltas.sum(axis=1)
    base_sum = float(sel_df.capital_usd.sum())
    peak = pcurve.cummax()
    dd_usd = float((pcurve - peak).min())
    ret_series = pcurve.diff().fillna(0) / base_sum
    sharpe = float(np.sqrt(365) * ret_series.mean() / ret_series.std()) if ret_series.std() else 0.0
    stats = {
        "strategies": int(len(sel_df)),
        "capital_usd": round(base_sum, 4),
        "net_pnl_usd": round(float(pcurve.iloc[-1]), 4),
        "roi_pct": round(float(pcurve.iloc[-1]) / base_sum * 100, 2),
        "max_drawdown_usd": round(dd_usd, 4),
        "max_drawdown_pct_of_capital": round(dd_usd / base_sum * 100, 2),
        "sharpe": round(sharpe, 3),
        "days": int(len(pcurve)),
        "trades": int(sel_df.m_trades.sum()),
        "win_rate_weighted": round(float((sel_df.m_wr * sel_df.m_trades).sum() / sel_df.m_trades.sum()), 2),
        "worst_loss_usd": round(float(sel_df.worst_loss_usd.sum()), 4),
        "worst_loss_pct_avail": round(float(sel_df.worst_loss_usd.sum()) / AVAIL * 100, 2),
        "util_pct": round(base_sum / AVAIL * 100, 2),
        "buffer_usd": round(AVAIL - base_sum, 4),
        "common_days": int(len(common)),
    }
    with open(os.path.join(OUT, "summary2.json"), "w", encoding="utf-8") as fh:
        json.dump(stats, fh, ensure_ascii=False, indent=2)
    pcurve.to_csv(os.path.join(OUT, "portfolio_curve2.csv"), header=["pnl_usd"], encoding="utf-8-sig")
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    print(sel_df.sort_values("capital_usd", ascending=False).to_string(index=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
