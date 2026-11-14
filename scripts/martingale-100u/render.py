#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Render charts (hand-rolled SVG, no matplotlib) and markdown tables."""
import json
import os
import sys

import numpy as np
import pandas as pd

sys.stdout.reconfigure(encoding="utf-8")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "reports", "martingale-100u")
IMG = os.path.join(OUT, "img")
os.makedirs(IMG, exist_ok=True)

COL = ["#2563eb", "#dc2626", "#16a34a", "#f59e0b", "#7c3aed", "#0891b2"]


def svg_open(w, h, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
            f'font-family="Segoe UI,Microsoft YaHei,sans-serif" font-size="11">'
            f'<rect width="{w}" height="{h}" fill="#ffffff"/>'
            f'<text x="14" y="20" font-size="13" font-weight="600" fill="#111827">{title}</text>')


def axis(x0, y0, x1, y1, ylabels, xlabels, color="#9ca3af"):
    s = ""
    for i, (v, lab) in enumerate(ylabels):
        y = y1 - (y1 - y0) * i / max(len(ylabels) - 1, 1)
        s += f'<line x1="{x0}" y1="{y:.1f}" x2="{x1}" y2="{y:.1f}" stroke="#f3f4f6"/>'
        s += f'<text x="{x0-6}" y="{y+4:.1f}" text-anchor="end" fill="{color}">{lab}</text>'
    for x, lab in xlabels:
        s += f'<line x1="{x:.1f}" y1="{y1}" x2="{x:.1f}" y2="{y1+4}" stroke="{color}"/>'
        s += f'<text x="{x:.1f}" y="{y1+16}" text-anchor="middle" fill="{color}">{lab}</text>'
    return s


def line_chart(path, title, xs, ys, yfmt, xfmt, height=300):
    W, H = 880, height
    L, R, T, B = 62, 18, 34, 42
    ymin, ymax = min(ys), max(ys)
    pad = (ymax - ymin) * 0.08 or 1
    ymin, ymax = ymin - pad, ymax + pad
    s = svg_open(W, H, title)
    n = len(ys)
    sx = lambda i: L + (W - L - R) * i / max(n - 1, 1)
    sy = lambda v: T + (H - T - B) * (1 - (v - ymin) / (ymax - ymin))
    yl = [(ymin + (ymax - ymin) * i / 4, yfmt(ymin + (ymax - ymin) * i / 4)) for i in range(5)]
    xl = [(sx(i), xfmt(xs[i])) for i in range(0, n, max(n // 8, 1))]
    s += axis(L, T, W - R, H - B, yl, xl)
    pts = " ".join(f"{sx(i):.1f},{sy(v):.1f}" for i, v in enumerate(ys))
    s += f'<polyline points="{pts}" fill="none" stroke="{COL[0]}" stroke-width="1.8"/>'
    s += f'<text x="{W-R}" y="{T-8}" text-anchor="end" fill="#6b7280">{len(ys)} points</text>'
    return s + "</svg>"


def bar_chart(path, title, labels, values, yfmt, height=300, color="#2563eb"):
    W, H = 880, height
    L, R, T, B = 62, 18, 34, 54
    lo, hi = min(0, min(values)), max(0, max(values))
    span = (hi - lo) or 1
    s = svg_open(W, H, title)
    n = len(values)
    bw = (W - L - R) / n * 0.62
    sy = lambda v: T + (H - T - B) * (1 - (v - lo) / span)
    yl = [(lo + span * i / 4, yfmt(lo + span * i / 4)) for i in range(5)]
    s += axis(L, T, W - R, H - B, yl, [])
    s += f'<line x1="{L}" y1="{sy(0):.1f}" x2="{W-R}" y2="{sy(0):.1f}" stroke="#6b7280"/>'
    for i, v in enumerate(values):
        x = L + (W - L - R) * (i + 0.5) / n - bw / 2
        y = sy(max(v, 0))
        h = abs(sy(v) - sy(0))
        c = color if v >= 0 else COL[1]
        s += f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{h:.1f}" fill="{c}" opacity="0.85"/>'
        s += f'<text x="{x+bw/2:.1f}" y="{H-B+16}" text-anchor="middle" fill="#6b7280" font-size="9">{labels[i]}</text>'
    return s + "</svg>"


def scatter(path, df):
    W, H = 880, 380
    L, R, T, B = 66, 20, 34, 46
    s = svg_open(W, H, "风险-收益分布：每点一个策略（气泡大小=占用资金）")
    xs = df.m_dd.values
    ys = df.m_ret.values
    xmax, ymax = xs.max() * 1.1, ys.max() * 1.06
    sx = lambda v: L + (W - L - R) * v / xmax
    sy = lambda v: T + (H - T - B) * (1 - v / ymax)
    yl = [(ymax * i / 4, f"{int(ymax*i/4)}%") for i in range(5)]
    xl = [(sx(xmax * i / 4), f"{int(xmax*i/4)}%") for i in range(4)]
    s += axis(L, T, W - R, H - B, yl, xl)
    s += f'<text x="{(L+W-R)/2:.0f}" y="{H-10}" text-anchor="middle" fill="#6b7280">全区间最大回撤（相对单策略占用资金）</text>'
    for _, r in df.iterrows():
        rad = 4 + 9 * np.sqrt(r.capital_usd / df.capital_usd.max())
        c = COL[1] if r.tier == "T2" else COL[0]
        s += (f'<circle cx="{sx(r.m_dd):.1f}" cy="{sy(r.m_ret):.1f}" r="{rad:.1f}" fill="{c}" opacity="0.55"/>'
              f'<text x="{sx(r.m_dd):.1f}" y="{sy(r.m_ret)-rad-3:.1f}" text-anchor="middle" font-size="8" fill="#374151">'
              f'{r.inst_id.split("-")[0]}</text>')
    s += f'<circle cx="{W-150}" cy="{T+14}" r="6" fill="{COL[0]}" opacity="0.6"/><text x="{W-140}" y="{T+18}" fill="#374151">T1（严格 OOS）</text>'
    s += f'<circle cx="{W-150}" cy="{T+32}" r="6" fill="{COL[1]}" opacity="0.6"/><text x="{W-140}" y="{T+36}" fill="#374151">T2（放宽 OOS）</text>'
    return s + "</svg>"


def write_md(s):
    return "<style>" + s + "</style>" if False else s


def md_table(df, cols, headers, fmts):
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for _, r in df.iterrows():
        cells = []
        for c, f in zip(cols, fmts):
            v = r[c]
            cells.append(f(v) if callable(f) else str(v))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


def main():
    sel = pd.read_csv(os.path.join(OUT, "selected.csv"))
    orders = pd.read_csv(os.path.join(OUT, "orders.csv"))
    summ = json.load(open(os.path.join(OUT, "summary.json"), encoding="utf-8"))
    specs = pd.read_csv(os.path.join(OUT, "specs.csv"))
    for c in ["ctVal", "lotSz", "minSz", "tickSz", "refPx", "notionalPerContractUsd", "minNotionalUsd", "maxLeverage", "funding_avg8h_pct", "bars1h"]:
        specs[c] = pd.to_numeric(specs[c], errors="coerce")
    curve = pd.read_csv(os.path.join(OUT, "portfolio_curve.csv"), parse_dates=["ts_ms"]).rename(columns={"ts_ms": "ts"})
    monthly = pd.read_csv(os.path.join(OUT, "portfolio_monthly.csv"), parse_dates=["ts_ms"]).rename(columns={"ts_ms": "ts", "0": "pnl_usd"})

    lay = orders[orders.layer > 0].groupby("idx").agg(layers=("layer", "size"), c_live=("margin_usd", "sum"))
    sel = sel.merge(lay, on="idx", how="left")
    sel["c_live"] = sel.c_live.round(2)
    sel["worst_live"] = (sel.c_live * sel.stopLossPct / 100).round(3)
    sel["pnl_pm"] = (sel.m_pnl / 10.06).round(2)

    # charts
    open(os.path.join(IMG, "equity.svg"), "w", encoding="utf-8").write(
        line_chart("equity.svg", "组合资金曲线（27 策略等权最小资金，统一时间轴叠加）",
                   list(range(len(curve))), curve.equity.values,
                   lambda v: f"{v:.0f}U", lambda i: pd.to_datetime(curve.ts.iloc[int(i)]).strftime("%m-%d")))
    open(os.path.join(IMG, "monthly.svg"), "w", encoding="utf-8").write(
        bar_chart("monthly.svg", "月度盈亏（USD，组合合计）",
                  [pd.to_datetime(t).strftime("%y-%m") for t in monthly.ts],
                  monthly.pnl_usd.values, lambda v: f"{v:.0f}"))
    top = sel.sort_values("c_live", ascending=False)
    open(os.path.join(IMG, "alloc.svg"), "w", encoding="utf-8").write(
        bar_chart("alloc.svg", "单策略资金占用（USD，已按最小下单量取整）",
                  [s.split("-")[0] for s in top.inst_id], top.c_live.values, lambda v: f"{v:.0f}", color="#7c3aed"))
    open(os.path.join(IMG, "scatter.svg"), "w", encoding="utf-8").write(scatter("scatter.svg", sel))

    # tables
    specs_m = md_table(specs.sort_values("minNotionalUsd"),
                       ["inst_id", "refPx", "ctVal", "lotSz", "minSz", "tickSz", "notionalPerContractUsd",
                        "minNotionalUsd", "maxLeverage", "funding_avg8h_pct", "bars1h"],
                       ["交易对", "参考价", "面值 ctVal", "最小下单量 lotSz", "最小张数 minSz", "最小变动价位 tickSz",
                        "每张名义(U)", "最小下单额(U)", "最大杠杆", "资金费均值(%/8h)", "1H 根数"],
                       [str, lambda v: f"{v:g}", lambda v: f"{v:g}", lambda v: f"{v:g}", lambda v: f"{v:g}",
                        lambda v: f"{v:g}", lambda v: f"{v:.4f}", lambda v: f"{v:.4f}",
                        lambda v: f"{v:g}", lambda v: f"{v:.5f}", lambda v: f"{v:.0f}"])
    open(os.path.join(OUT, "tbl_specs.md"), "w", encoding="utf-8").write(specs_m)

    sel_s = sel.sort_values("idx")
    sel_m = md_table(sel_s,
                     ["idx", "inst_id", "side_cn", "leverage", "maxLayers", "layerStepPct", "layerScale",
                      "takeProfitPct", "stopLossPct", "m_trades", "m_wr", "m_ret", "m_dd", "m_pnl", "roi_10m_pct"],
                     ["#", "交易对", "方向", "杠杆", "层数", "间距%", "加仓倍数", "止盈%", "止损%",
                      "全区间笔数", "胜率%", "收益%", "最大回撤%", "净利(U)", "ROI%"],
                     ["{:.0f}".format, lambda v: v.replace("-USDT-SWAP", ""), str, "{:.0f}".format,
                      "{:.0f}".format, "{:.1f}".format, "{:.1f}".format, "{:.1f}".format, "{:.0f}".format,
                      "{:.0f}".format, "{:.2f}".format, "{:.0f}".format, "{:.0f}".format, "{:.2f}".format,
                      "{:.0f}".format])
    open(os.path.join(OUT, "tbl_select.md"), "w", encoding="utf-8").write(sel_m)

    alloc_m = md_table(sel_s,
                       ["idx", "inst_id", "side_cn", "leverage", "layers", "minNotionalUsd", "c_live", "worst_live", "pnl_pm"],
                       ["#", "交易对", "方向", "杠杆", "层数", "首层名义(U)", "占用保证金(U)", "单次全止损亏损(U)", "月均净利(U)"],
                       ["{:.0f}".format, lambda v: v.replace("-USDT-SWAP", ""), str, "{:.0f}".format,
                        "{:.0f}".format, lambda v: f"{v:.4f}", lambda v: f"{v:.2f}",
                        lambda v: f"{v:.3f}", lambda v: f"{v:.2f}"])
    open(os.path.join(OUT, "tbl_alloc.md"), "w", encoding="utf-8").write(alloc_m)

    o = orders[orders.layer > 0].copy()
    o["side"] = o.side.map({"long": "多", "short": "空"})
    o["name"] = o.instId.str.replace("-USDT-SWAP", "", regex=False)
    o_m = md_table(o, ["idx", "name", "side", "layer", "trigger_px", "sz_contracts", "coins", "notional_usd",
                       "margin_usd", "cum_margin_usd"],
                   ["#", "交易对", "方向", "层", "触发价", "下单张数 sz", "币数量", "名义(U)", "本层保证金(U)", "累计保证金(U)"],
                   ["{:.0f}".format, str, str, "{:.0f}".format, lambda v: f"{v:g}", lambda v: f"{v:g}",
                    lambda v: f"{v:g}", lambda v: f"{v:.4f}", lambda v: f"{v:.4f}", lambda v: f"{v:.4f}"])
    open(os.path.join(OUT, "tbl_orders.md"), "w", encoding="utf-8").write(o_m)

    mon_m = md_table(monthly, ["ts", "pnl_usd"],
                     ["月份", "盈亏(U)"],
                     [lambda v: pd.to_datetime(v).strftime("%Y-%m"), lambda v: f"{v:+.2f}"])
    open(os.path.join(OUT, "tbl_monthly.md"), "w", encoding="utf-8").write(mon_m)

    out = {
        "n": int(len(sel)), "c_bt": round(float(sel.capital_usd.sum()), 2),
        "c_live": round(float(sel.c_live.sum()), 2), "worst_live": round(float(sel.worst_live.sum()), 2),
        "worst_pct_of_avail": round(float(sel.worst_live.sum()) / 90.271 * 100, 2),
        "util_pct": round(float(sel.c_live.sum()) / 90.271 * 100, 2),
        "buffer": round(90.271 - float(sel.c_live.sum()), 2),
        "pnl_total": round(float(sel.m_pnl.sum()), 2),
        "layers_hist": {int(k): int(v) for k, v in sel.layers.value_counts().sort_index().items()},
        "lev_hist": {int(k): int(v) for k, v in sel.leverage.value_counts().sort_index().items()},
        "side_hist": {k: int(v) for k, v in sel.side_cn.value_counts().items()},
        "tp_hist": {float(k): int(v) for k, v in sel.takeProfitPct.value_counts().sort_index().items()},
        "step_hist": {float(k): int(v) for k, v in sel.layerStepPct.value_counts().sort_index().items()},
        "scale_hist": {float(k): int(v) for k, v in sel.layerScale.value_counts().sort_index().items()},
        "sl_hist": {float(k): int(v) for k, v in sel.stopLossPct.value_counts().sort_index().items()},
        "avg_hold": round(float(sel.m_hold.mean()), 2),
        "trades_total": int(sel.m_trades.sum()),
    }
    json.dump(out, open(os.path.join(OUT, "final_stats.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
