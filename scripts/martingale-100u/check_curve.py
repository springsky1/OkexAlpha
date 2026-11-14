# -*- coding: utf-8 -*-
"""检查 compare_summary.json 里组合曲线的端点与最大回撤发生日。"""
import json
from pathlib import Path

p = Path(r"D:\workplace\Github\OkexAlpha\reports\martingale-100u\compare_summary.json")
s = json.loads(p.read_text(encoding="utf-8"))
for g, d in s.items():
    c = d["curve"]
    vals = [v for _, v in c]
    peak, mdd, at = -1e18, 0.0, None
    for i, v in enumerate(vals):
        peak = max(peak, v)
        if v - peak < mdd:
            mdd, at = v - peak, i
    print(g, "pts", len(c), "end", round(vals[-1], 2), "peak", round(max(vals), 2),
          "min", round(min(vals), 2), "mdd", round(mdd, 2), "at_idx", at)
    # 该日前后最近 4 个点
    lo = max(0, at - 2)
    print("   around:", [(c[i][0], round(c[i][1], 1)) for i in range(lo, min(len(c), at + 2))])
    print("   last3:", [(c[i][0], round(c[i][1], 1)) for i in range(len(c) - 3, len(c))])
