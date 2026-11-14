#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
legacy（线上 SHADOW #1 参数）vs 马丁格尔 2 层（多/空）三组对比。

口径（与引擎实现一致，见 b2/b4 摘要）：
  * 单币资金 C = initialMarginUsd × Σ_{i=1..maxLayers} layerScale^(i-1)
  * 收益用 COIN 行（全区间 2026-01-01→11-07），ALL 行是 70% 样本内口径，不可用于跨组比较
  * 组合曲线 = 各币 (equity - 首点) 逐日相加，得到累计盈亏 USD
  * 回撤 = 累计盈亏相对其历史峰值的最大回落；同时给 /ΣC 与 /ΣI 两种比例
输出 reports/martingale-100u/compare_summary.json、tbl_compare.md、tbl_compare_coins.md
"""
import csv
import io
import json
import math
import os
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(r"D:\workplace\Github\OkexAlpha")
OUT = ROOT / "reports" / "martingale-100u"
OUT.mkdir(parents=True, exist_ok=True)
PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
AVAILABLE = 90.27123346585422   # 实测可用 USDT
BUDGET = 90.27                  # 用户口径 100U 的可用上限

COIN_SQL = """
select r.label, x.inst_id, x.net_pnl, x.net_pnl_pct, x.max_drawdown_pct, x.sharpe,
       x.trade_count, x.win_rate, x.liquidation_count, x.verdict, x.oos_net_pnl_pct,
       x.params::text as params
from okx_backtest_result x
join okx_backtest_run r on r.id = x.run_id
where r.label like 'CMP-%' and x.segment = 'COIN'
order by r.label
"""

EQ_SQL = """
select r.label, q.ts_ms, q.equity
from okx_backtest_equity q
join okx_backtest_result t on t.id = q.result_id
join okx_backtest_run r on r.id = t.run_id
where r.label like 'CMP-%'
order by r.label, q.ts_ms
"""


def psql_copy(sql: str) -> list[dict]:
    env = dict(os.environ)
    env["PGPASSWORD"] = "123456"
    p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                        "-c", f"copy ({sql}) to stdout with (format csv, header true)"],
                       capture_output=True, text=True, encoding="utf-8", env=env)
    if p.returncode != 0:
        sys.exit("psql failed: " + (p.stderr or "")[:800])
    return list(csv.DictReader(io.StringIO(p.stdout)))


def fnum(v, d=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


SETS = ("legacy", "mart-long", "mart-short")


def set_of(label: str):
    rest = label[len("CMP-"):]
    for s in ("mart-long", "mart-short", "legacy"):
        if rest.startswith(s + "-"):
            return s, rest[len(s) + 1:]
    return None, rest


def main():
    coins = psql_copy(COIN_SQL)
    eq = psql_copy(EQ_SQL)

    by_set = defaultdict(list)
    for c in coins:
        s, sym = set_of(c["label"])
        p = json.loads(c["params"]) if c["params"] else {}
        lay = int(fnum(p.get("maxLayers"), 1))
        sc = fnum(p.get("layerScale"), 1.0)
        im = fnum(p.get("initialMarginUsd"))
        c["symbol"] = sym
        c["set"] = s
        c["im"] = im
        c["leverage"] = fnum(p.get("leverage"))
        c["capital"] = im * sum(sc ** i for i in range(max(1, lay)))
        c["net_pnl"] = fnum(c["net_pnl"])
        c["net_pnl_pct"] = fnum(c["net_pnl_pct"])
        c["max_drawdown_pct"] = fnum(c["max_drawdown_pct"])
        c["trade_count"] = int(fnum(c["trade_count"]))
        c["win_rate"] = fnum(c["win_rate"])
        c["liquidations"] = int(fnum(c["liquidation_count"]))
        if s:
            by_set[s].append(c)

    series = defaultdict(list)
    for row in eq:
        series[row["label"]].append((fnum(row["ts_ms"]), fnum(row["equity"])))

    summary = {}
    for s in SETS:
        rs = by_set.get(s, [])
        if not rs:
            continue
        cap = sum(r["capital"] for r in rs)
        im_sum = sum(r["im"] for r in rs)
        pnl = sum(r["net_pnl"] for r in rs)
        trades = sum(r["trade_count"] for r in rs)
        wins = sum(r["win_rate"] / 100.0 * r["trade_count"] for r in rs)
        liq = sum(r["liquidations"] for r in rs)

        # 组合累计盈亏曲线：每币先取"当日最后一个点"，再按公共日轴前向填充，最后相加并减去各自首值
        per_coin = {}
        all_days = set()
        for r in rs:
            pts = sorted(series.get(r["label"], []))
            if not pts:
                continue
            day_val = {}
            for ts, v in pts:
                day_val[ts // 86400000 * 86400000] = v
            per_coin[r["label"]] = day_val
            all_days.update(day_val)
        days = sorted(all_days)
        curve = []
        last = {}
        for d in days:
            total = 0.0
            for lab, dv in per_coin.items():
                if d in dv:
                    last[lab] = dv[d]
                if lab in last:
                    total += last[lab]
            curve.append((d, total))
        base_total = sum(v for v in (list(dv.values())[0] for dv in per_coin.values()))
        curve = [(d, v - base_total) for d, v in curve]
        peak = -1e18
        mdd = 0.0
        for _, v in curve:
            peak = max(peak, v)
            mdd = min(mdd, v - peak)
        # 日收益序列（相对部署资金 C 的固定基数）
        dret = []
        prev = 0.0
        for _, v in curve:
            dret.append((v - prev) / cap if cap else 0.0)
            prev = v
        mu = sum(dret) / len(dret) if dret else 0.0
        sd = (math.sqrt(sum((x - mu) ** 2 for x in dret) / (len(dret) - 1))
              if len(dret) > 1 else 0.0)
        sharpe_ann = mu / sd * math.sqrt(365) if sd > 0 else 0.0

        # 按预算缩放：把整组等比缩放到 BUDGET，看同一笔钱能挣多少
        k = BUDGET / cap if cap else 0.0

        summary[s] = {
            "coins": len(rs),
            "capital_usd": round(cap, 2),
            "initial_margin_usd": round(im_sum, 2),
            "net_pnl_usd": round(pnl, 2),
            "return_on_capital_pct": round(pnl / cap * 100, 1) if cap else 0,
            "return_on_available_pct": round(pnl / AVAILABLE * 100, 1),
            "margin_util_pct": round(cap / AVAILABLE * 100, 1),
            "scaled_pnl_on_90u": round(pnl * k, 2),
            "scale_factor": round(k, 3),
            "trades": trades,
            "win_rate_pct": round(wins / trades * 100, 2) if trades else 0,
            "max_dd_usd": round(mdd, 2),
            "max_dd_pct_of_capital": round(mdd / cap * 100, 2) if cap else 0,
            "max_dd_pct_of_initial": round(mdd / im_sum * 100, 2) if im_sum else 0,
            "sharpe_ann": round(sharpe_ann, 2),
            "liquidations": liq,
            "avg_trade_pnl_usd": round(pnl / trades, 4) if trades else 0,
            "daily_sigma_pct": round(sd * 100, 3),
            "worst_coin": min(rs, key=lambda r: r["net_pnl"])["symbol"],
            "equity_points": len(curve),
            "curve": curve,
            "coins_detail": [
                {"symbol": r["symbol"], "side": "多" if s != "mart-short" else "空",
                 "pnl": round(r["net_pnl"], 3), "ret_pct": round(r["net_pnl_pct"], 1),
                 "dd_pct": round(r["max_drawdown_pct"], 1), "trades": r["trade_count"],
                 "win_rate": round(r["win_rate"], 1), "liq": r["liquidations"],
                 "capital": round(r["capital"], 3), "leverage": r["leverage"]}
                for r in sorted(rs, key=lambda x: -x["net_pnl"])
            ],
        }

    (OUT / "compare_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    rows_md = []

    def row(name, fn):
        rows_md.append([name] + [fn(summary[s]) for s in SETS if s in summary])

    rows_md.append(["指标"] + [{"legacy": "legacy（线上原策略）", "mart-long": "马丁-多",
                               "mart-short": "马丁-空"}[s] for s in SETS if s in summary])
    row("交易币数", lambda x: f"{x['coins']}")
    row("部署保证金 ΣC (U)", lambda x: f"{x['capital_usd']:.2f}")
    row("占总可用 90.27U", lambda x: f"{x['margin_util_pct']:.0f}%")
    row("区间净利 (U)", lambda x: f"{x['net_pnl_usd']:+.2f}")
    row("对部署资金收益", lambda x: f"{x['return_on_capital_pct']:+.1f}%")
    row("缩放到 90.27U 的净利", lambda x: f"{x['scaled_pnl_on_90u']:+.2f}")
    row("最大回撤 (U)", lambda x: f"{x['max_dd_usd']:+.2f}")
    row("回撤 / 部署资金", lambda x: f"{x['max_dd_pct_of_capital']:.1f}%")
    row("夏普（年化，日频）", lambda x: f"{x['sharpe_ann']:.2f}")
    row("交易笔数", lambda x: f"{x['trades']:,}")
    row("胜率", lambda x: f"{x['win_rate_pct']:.2f}%")
    row("单笔均利 (U)", lambda x: f"{x['avg_trade_pnl_usd']:+.4f}")
    row("强平次数", lambda x: f"{x['liquidations']}")
    md = "\n".join("| " + " | ".join(r) + " |" for r in rows_md)
    (OUT / "tbl_compare.md").write_text(md + "\n", encoding="utf-8")

    # 明细表：三组 × 币
    lines = ["| 分组 | 币种 | 方向 | 净利(U) | 收益率 | 最大回撤 | 笔数 | 胜率 | 强平 | 部署资金(U) | 杠杆 |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for s in SETS:
        for d in summary.get(s, {}).get("coins_detail", []):
            lines.append(f"| {s} | {d['symbol']} | {d['side']} | {d['pnl']:+.3f} | {d['ret_pct']:+.1f}% | "
                         f"{d['dd_pct']:.1f}% | {d['trades']} | {d['win_rate']:.1f}% | {d['liq']} | "
                         f"{d['capital']:.2f} | {d['leverage']:.0f}x |")
    (OUT / "tbl_compare_coins.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    for s in SETS:
        if s in summary:
            print(s, json.dumps({k: v for k, v in summary[s].items()
                                 if k not in ("curve", "coins_detail")}, ensure_ascii=False))
    print("wrote compare_summary.json / tbl_compare.md / tbl_compare_coins.md")


if __name__ == "__main__":
    main()
