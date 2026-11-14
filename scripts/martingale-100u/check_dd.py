# -*- coding: utf-8 -*-
"""核对：①既有 27 策略组合曲线的回撤；②CMP 各组回撤来源拆解。"""
import csv
import math
from pathlib import Path

ROOT = Path(r"D:\workplace\Github\OkexAlpha")
OUT = ROOT / "reports" / "martingale-100u"

rows = list(csv.DictReader((OUT / "portfolio_curve.csv").open(encoding="utf-8-sig")))
pnl = [float(r["pnl_usd"]) for r in rows]
peak, mdd, mdd_at = -1e18, 0.0, None
for i, v in enumerate(pnl):
    peak = max(peak, v)
    if v - peak < mdd:
        mdd, mdd_at = v - peak, i
cap = 47.148
print(f"27-strategy: points={len(pnl)} end={pnl[-1]:.2f} max_dd_usd={mdd:.2f} "
      f"dd/capital={mdd/cap*100:.2f}% at day {mdd_at} ({rows[mdd_at]['ts_ms']})")

# 日收益 sigma（相对 cap）
d = [pnl[0]] + pnl
rets = [(d[i] - d[i - 1]) / cap for i in range(1, len(d))]
mu = sum(rets) / len(rets)
sd = math.sqrt(sum((x - mu) ** 2 for x in rets) / (len(rets) - 1))
print(f"daily sigma={sd*100:.3f}%  sharpe={mu/sd*math.sqrt(365):.2f}")
