# -*- coding: utf-8 -*-
"""按分组统计逐笔交易的持仓时长、触发层数、离场原因与最差单笔。"""
import csv
import io
import os
import subprocess
from collections import defaultdict

PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
env = dict(os.environ)
env["PGPASSWORD"] = "123456"

SQL = """
select r.label, t.exit_reason, t.layers, t.pnl, t.pnl_pct, t.notional_usd,
       (t.exit_ts_ms - t.entry_ts_ms) / 3600000.0 as hold_h
from okx_backtest_trade t join okx_backtest_run r on r.id = t.run_id
where r.label like 'CMP-%'
"""

p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                    "-c", f"copy ({SQL}) to stdout with (format csv, header true)"],
                   capture_output=True, text=True, encoding="utf-8", env=env)
rows = list(csv.DictReader(io.StringIO(p.stdout)))


def set_of(label):
    rest = label[4:]
    for s in ("mart-long", "mart-short", "legacy"):
        if rest.startswith(s + "-"):
            return s
    return None


agg = defaultdict(lambda: {"n": 0, "hold": 0.0, "l2": 0, "l3": 0, "worst": 0.0,
                           "best": 0.0, "src": defaultdict(int)})
for r in rows:
    s = set_of(r["label"])
    if not s:
        continue
    a = agg[s]
    a["n"] += 1
    a["hold"] += float(r["hold_h"] or 0)
    lay = int(r["layers"] or 1)
    if lay >= 2:
        a["l2"] += 1
    if lay >= 3:
        a["l3"] += 1
    pnl = float(r["pnl"] or 0)
    a["worst"] = min(a["worst"], pnl)
    a["best"] = max(a["best"], pnl)
    a["src"][r["exit_reason"]] += 1

for s, a in agg.items():
    n = a["n"]
    print(f"{s}: n={n} avg_hold={a['hold']/n:.2f}h layer2+={a['l2']/n*100:.1f}% "
          f"layer3+={a['l3']/n*100:.1f}% worst={a['worst']:+.3f} best={a['best']:+.3f}")
    print("   exits:", dict(sorted(a["src"].items(), key=lambda x: -x[1])))
