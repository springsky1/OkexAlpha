#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Final deliverables for the 100U martingale study (selection rules R1/R2/R3)."""
import io
import json
import math
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
AVAIL = 90.27123346585422
DEPLOY = 72.217
CAP_PER = 9.02          # R1: <=10% of available per strategy
LOSS_PER = 1.81         # R2: worst-case stop loss <=2% of available
WORKSPACE = ROOT


def q(sql):
    p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha", "-c",
                        "copy (" + sql + ") to stdout with (format csv, header true)"],
                       capture_output=True, env=ENV)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return pd.read_csv(io.BytesIO(p.stdout), encoding="utf-8")


CHAMPS = f"""
select run.id run_id, run.label, a.id all_id, a.param_key, a.params::text params,
       a.verdict, (a.metrics->>'score')::float8 score, a.oos_net_pnl_pct oos_ret,
       (a.metrics->'inSample'->>'trades')::int is_trades,
       (a.metrics->'outOfSample'->>'trades')::int oos_trades,
       (a.metrics->'outOfSample'->>'maxDrawdownPct')::float8 oos_dd,
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
       (c.metrics->'metrics'->>'avgHoldHours')::float8 m_hold
from okx_backtest_result a
join okx_backtest_run run on run.id = a.run_id
join okx_backtest_result c on c.run_id = a.run_id and c.segment = 'COIN' and c.param_key = a.param_key
where {LABELS} and a.segment = 'ALL' and a.rank = 1
"""

TRADES = f"""
select t.result_id, count(*) n_tr, max(t.layers) max_layers, max(t.notional_usd) max_notional
from okx_backtest_trade t join okx_backtest_run run on run.id = t.run_id
where {LABELS} group by 1
"""

EQUITY = f"""
select e.result_id, e.ts_ms, e.equity from okx_backtest_equity e
join okx_backtest_result c on c.id = e.result_id
join okx_backtest_run run on run.id = c.run_id
where {LABELS} order by e.result_id, e.ts_ms
"""

KLINES = """
select inst_id, ts_ms, open, high, low, close from okx_kline
where bar='1H' and kline_type='CANDLE' and ts_ms >= 1767196800000 and ts_ms <= 1794031200000
"""


def explode(df, col="params"):
    pj = pd.json_normalize(df[col].map(json.loads))
    return df.drop(columns=[col]).join(pj)


def build_pool(ch):
    strict = ((ch.verdict == "CANDIDATE") & (ch.m_liq == 0) & (ch.m_trades >= 100) &
              (ch.m_pnl > 0) & (ch.m_dd <= 110) & (ch.oos_trades >= 30) & (ch.oos_dd < 25))
    relax = ((ch.m_liq == 0) & (ch.m_trades >= 100) & (ch.m_pnl > 0) & (ch.m_dd <= 120) &
             (ch.oos_trades >= 30) & (ch.oos_dd < 35) & (ch.oos_ret > 0))
    t1 = ch[strict].sort_values("score", ascending=False).groupby("inst_id", as_index=False).first()
    t1["tier"] = "T1"
    t2 = ch[relax & ~ch.inst_id.isin(t1.inst_id)].sort_values("score", ascending=False) \
        .groupby("inst_id", as_index=False).first()
    t2["tier"] = "T2"
    return pd.concat([t1, t2], ignore_index=True).sort_values("score", ascending=False)


def main():
    ch = explode(q(CHAMPS))
    ch["S"] = [sum(s ** i for i in range(int(ml))) for ml, s in zip(ch.maxLayers, ch.layerScale)]
    ch["capital_min"] = ch.initialMarginUsd * ch.S
    ch = ch.merge(q(TRADES), left_on="coin_id", right_on="result_id", how="left")
    ch["avg_layer_used"] = ch.max_layers
    ch["max_notional"] = ch.max_notional.fillna(0)

    pool = build_pool(ch)
    print("pool", len(pool), "T1", (pool.tier == "T1").sum(), "sum", round(pool.capital_min.sum(), 2))

    # rules
    pool["r1"] = pool.capital_min <= CAP_PER
    pool["r2"] = pool.capital_min * pool.stopLossPct / 100 <= LOSS_PER
    pool["r4"] = pool.score > 0
    keep = pool[pool.r1 & pool.r2 & pool.r4].copy()
    keep = keep.sort_values("score", ascending=False)
    keep["cum"] = keep.capital_min.cumsum()
    print("kept", len(keep), "sum", round(keep.capital_min.sum(), 2),
          "cum<=DEPLOY all:", bool((keep.cum <= DEPLOY).all()))

    # equity
    eq = q(EQUITY)
    piv = eq.pivot(index="ts_ms", columns="result_id", values="equity").sort_index().ffill()

    def portfolio_stats(sel, label):
        cols = {}
        for _, r in sel.iterrows():
            if r.coin_id in piv.columns:
                cols[r.inst_id] = (piv[r.coin_id] - r.initialMarginUsd).fillna(0)
        pnl = pd.DataFrame(cols)
        pnl.index = pd.to_datetime(pnl.index, unit="ms", utc=True).tz_convert("Asia/Shanghai")
        port = pnl.sum(axis=1)
        cap = float(sel.capital_min.sum())
        eqv = cap + port
        dd = eqv / eqv.cummax() - 1
        ret = port.diff().fillna(port.iloc[0]) / cap
        corr = pnl.pct_change().dropna().corr().values
        iu = np.triu_indices(len(pnl.columns), 1)
        return {
            "label": label, "n": len(cols), "capital": round(cap, 2),
            "pnl": round(float(port.iloc[-1]), 2),
            "ret_pct": round(float(port.iloc[-1]) / cap * 100, 2),
            "max_dd_pct": round(float(dd.min()) * 100, 2),
            "sharpe_d": round(float(ret.mean() / ret.std() * math.sqrt(365)), 2) if ret.std() > 0 else 0,
            "worst_day_pct": round(float(ret.min()) * 100, 2),
            "best_day_pct": round(float(ret.max()) * 100, 2),
            "corr": round(float(corr[iu].mean()), 3),
            "monthly_avg_pct": round(float(port.resample("ME").last().diff().mean() / cap * 100), 2),
        }, pnl, port, dd

    s_all, pnl_all, port_all, dd_all = portfolio_stats(keep, "P2")
    t1only = keep[keep.tier == "T1"]
    s_t1, _, _, _ = portfolio_stats(t1only, "P3(T1 only)")
    print(json.dumps(s_all, ensure_ascii=False))
    print(json.dumps(s_t1, ensure_ascii=False))

    # ---------------- per strategy outputs
    keep = keep.reset_index(drop=True)
    keep["idx"] = keep.index + 1
    keep["capital_usd"] = keep.capital_min.round(4)
    keep["worst_loss_usd"] = (keep.capital_min * keep.stopLossPct / 100).round(3)
    keep["stop_dist_pct"] = (keep.stopLossPct / 100 / keep.leverage * 100).round(2)
    keep["liq_dist_pct"] = ((1 / keep.leverage - 0.005) * 100).round(2)
    keep["pnl_per_month"] = (keep.m_pnl / 10.06).round(2)
    keep["roi_10m_pct"] = (keep.m_pnl / keep.capital_min * 100).round(1)
    keep["side_cn"] = keep.side.map({"long": "多", "short": "空"})
    keep.to_csv(os.path.join(OUT, "selected.csv"), index=False)

    # ---------------- ladders
    with open(os.path.join(ROOT, "config", "martingale-100u.json"), encoding="utf-8") as f:
        CFG = json.load(f)
    specs = pd.DataFrame([{
        "inst_id": c["instId"], "ctVal": c["ctVal"], "minSz": c["minSz"], "lotSz": c["lotSz"],
        "tickSz": c["tickSz"], "refPx": c["refPx"],
        "notionalPerContractUsd": c["notionalPerContractUsd"], "minNotionalUsd": c["minNotionalUsd"],
        "maxLeverage": c["maxLeverage"], "funding_avg8h_pct": round(c["funding"]["avg8h"] * 100, 5),
        "bars1h": c["kline1H"]["bars"],
    } for c in CFG["coins"]])
    keep = keep.merge(specs, on="inst_id", how="left")
    specs.to_csv(os.path.join(OUT, "specs.csv"), index=False)
    keep.to_csv(os.path.join(OUT, "selected.csv"), index=False)

    rows = []
    for _, r in keep.iterrows():
        px = float(r.refPx)
        notionals, pxs, lots_list = [], [], []
        for layer in range(1, int(r.maxLayers) + 1):
            notional = float(r.minNotionalUsd) * (r.layerScale ** (layer - 1))
            lots = math.ceil(notional / float(r.notionalPerContractUsd) / float(r.lotSz) - 1e-9) * float(r.lotSz)
            lots = max(lots, float(r.minSz))
            actual = lots * float(r.notionalPerContractUsd)
            notionals.append(actual)
            pxs.append(px)
            lots_list.append(lots)
            px = px * (1 - r.layerStepPct / 100) if r.side == "long" else px * (1 + r.layerStepPct / 100)
        tot_notional = sum(notionals)
        avg = sum(n * p for n, p in zip(notionals, pxs)) / tot_notional
        margin_total = tot_notional / r.leverage
        stop_px = avg * (1 - r.stopLossPct / 100 / r.leverage) if r.side == "long" else avg * (1 + r.stopLossPct / 100 / r.leverage)
        qty = sum(notionals) / avg
        if r.side == "long":
            liq_px = (tot_notional - margin_total) / (qty * 0.995)
        else:
            liq_px = (tot_notional + margin_total) / (qty * 1.005)
        for layer, (n, p, l) in enumerate(zip(notionals, pxs, lots_list), start=1):
            rows.append({
                "idx": r.idx, "instId": r.inst_id, "side": r.side, "layer": layer,
                "trigger_px": round(p, 8), "sz_contracts": l, "coins": round(l * float(r.ctVal), 6),
                "notional_usd": round(n, 4), "margin_usd": round(n / r.leverage, 4),
                "cum_margin_usd": round(sum(notionals[:layer]) / r.leverage, 4),
            })
        rows.append({
            "idx": r.idx, "instId": r.inst_id, "side": r.side, "layer": 0,
            "trigger_px": None, "sz_contracts": None, "coins": None,
            "notional_usd": round(tot_notional, 4), "margin_usd": round(margin_total, 4),
            "cum_margin_usd": None, "avg_entry": round(avg, 8), "stop_px": round(stop_px, 8),
            "liq_px": round(liq_px, 8),
        })
    lad = pd.DataFrame(rows)
    lad.to_csv(os.path.join(OUT, "orders.csv"), index=False)

    # ---------------- risk: empirical 1H adverse gap per coin
    kl = q(KLINES)
    kl["range_pct"] = (kl.high - kl.low) / kl.open * 100
    kl["adv_long"] = (kl.open - kl.low) / kl.open * 100
    kl["adv_short"] = (kl.high - kl.open) / kl.open * 100
    gap = kl.groupby("inst_id").agg(bars=("ts_ms", "size"), max_adv_long=("adv_long", "max"),
                                    max_adv_short=("adv_short", "max"),
                                    p999_long=("adv_long", lambda x: x.quantile(0.999)),
                                    p999_short=("adv_short", lambda x: x.quantile(0.999))).reset_index()
    risk = keep.merge(gap, left_on="inst_id", right_on="inst_id", how="left")
    risk["gap_beyond_stop"] = np.where(risk.side == "long",
                                       risk.max_adv_long > risk.stop_dist_pct,
                                       risk.max_adv_short > risk.stop_dist_pct)
    risk["gap_beyond_liq"] = np.where(risk.side == "long",
                                      risk.max_adv_long > risk.liq_dist_pct,
                                      risk.max_adv_short > risk.liq_dist_pct)
    risk.to_csv(os.path.join(OUT, "risk.csv"), index=False)
    print("gap beyond stop:", int(risk.gap_beyond_stop.sum()), "beyond liq:", int(risk.gap_beyond_liq.sum()))

    # portfolio curve file
    curve = pd.DataFrame({"pnl_usd": port_all, "equity": float(keep.capital_min.sum()) + port_all,
                          "dd_pct": dd_all * 100})
    curve.to_csv(os.path.join(OUT, "portfolio_curve.csv"))

    # monthly
    monthly = port_all.resample("ME").last().diff().fillna(port_all.resample("ME").last().iloc[0])
    monthly.to_csv(os.path.join(OUT, "portfolio_monthly.csv"))

    summary = {
        "account": {"avail": AVAIL, "deploy_cap": DEPLOY},
        "rules": {"cap_per_strategy_usd": CAP_PER, "max_single_trade_loss_usd": LOSS_PER,
                  "total_cap_usd": DEPLOY},
        "selected": {"n": len(keep), "t1": int((keep.tier == "T1").sum()),
                     "t2": int((keep.tier == "T2").sum()),
                     "capital": round(float(keep.capital_min.sum()), 2),
                     "utilization_pct": round(float(keep.capital_min.sum()) / AVAIL * 100, 2),
                     "buffer": round(AVAIL - float(keep.capital_min.sum()), 2),
                     "worst_case_all_stop_loss": round(float(keep.worst_loss_usd.sum()), 2)},
        "portfolio": s_all, "portfolio_t1only": s_t1,
        "monthly": {str(k.date()): round(float(v), 2) for k, v in monthly.items()},
    }
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2, default=float)
    print(json.dumps(summary["selected"], ensure_ascii=False))
    print(json.dumps(summary["monthly"], ensure_ascii=False))


if __name__ == "__main__":
    main()
