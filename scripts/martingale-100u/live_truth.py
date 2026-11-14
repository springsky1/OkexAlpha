# -*- coding: utf-8 -*-
"""用 ctVal 修正影子账本：真实已实现盈亏、真实止损距离。"""
import csv
import io
import os
import subprocess
from collections import defaultdict

PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
env = dict(os.environ)
env["PGPASSWORD"] = "123456"

SQL = """
select p.inst_id, p.side, p.entry_px, p.mark_px, p.sz, p.margin_usd,
       p.notional_usd, p.status, p.leverage, p.unrealized_pnl,
       coalesce(m.ct_val, 1) as ct_val
from okx_shadow_position p
left join okx_min_order m on m.inst_id = p.inst_id
where p.strategy_id = 1
"""
p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                    "-c", f"copy ({SQL}) to stdout with (format csv, header true)"],
                   capture_output=True, text=True, encoding="utf-8", env=env)
rows = list(csv.DictReader(io.StringIO(p.stdout)))
print("rows", len(rows), "cols", rows[0].keys() if rows else None)
tot_true = 0.0
tot_book = 0.0
per = defaultdict(lambda: [0, 0.0, 0.0])
for r in rows:
    if r["status"] != "CLOSED":
        continue
    ct = float(r["ct_val"] or 1)
    sz = float(r["sz"])
    e, m = float(r["entry_px"]), float(r["mark_px"])
    sign = 1 if r["side"] == "long" else -1
    true_pnl = float(r["notional_usd"] or 0) * (m / e - 1.0) * sign
    book_pnl = float(r["unrealized_pnl"] or 0)
    tot_true += true_pnl
    tot_book += book_pnl
    k = r["inst_id"]
    per[k][0] += 1
    per[k][1] += true_pnl
    per[k][2] += book_pnl

print(f"closed {sum(v[0] for v in per.values())}  true={tot_true:.2f}U  ledger={tot_book:.2f}U")
for k, v in sorted(per.items(), key=lambda x: x[1][1])[:10]:
    print(f"  {k:16s} n={v[0]:3d} true={v[1]:+9.2f} ledger={v[2]:+10.2f}")
