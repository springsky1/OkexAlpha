# -*- coding: utf-8 -*-
"""扫描 CMP-* 每币 equity 曲线，找出 (end-start) 与 net_pnl 偏差最大的币。"""
import csv
import io
import os
import subprocess
from collections import defaultdict

PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
env = dict(os.environ)
env["PGPASSWORD"] = "123456"

SQL = """
select r.label, q.ts_ms, q.equity
from okx_backtest_equity q
join okx_backtest_result t on t.id = q.result_id
join okx_backtest_run r on r.id = t.run_id
where r.label like 'CMP-mart-long-%'
order by r.label, q.ts_ms
"""
p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                    "-c", f"copy ({SQL}) to stdout with (format csv, header true)"],
                   capture_output=True, text=True, encoding="utf-8", env=env)
rows = list(csv.DictReader(io.StringIO(p.stdout)))
d = defaultdict(list)
for r in rows:
    d[r["label"]].append(float(r["equity"]))
res = sorted(((v[-1] - v[0], k, v[0], max(v), min(v), v[-1]) for k, v in d.items()), reverse=True)
for x in res[:6]:
    print(round(x[0], 2), x[1], "I", round(x[2], 3), "max", round(x[3], 2), "min", round(x[4], 2), "end", round(x[5], 2))
print("--- bottom ---")
for x in res[-4:]:
    print(round(x[0], 2), x[1], "I", round(x[2], 3), "min", round(x[4], 2), "end", round(x[5], 2))
