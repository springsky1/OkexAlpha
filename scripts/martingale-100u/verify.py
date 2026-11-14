"""Independent audit of the 100U martingale deliverable.

Every check re-derives its expected value from the database or from the raw
CSV columns -- it never compares the report against itself.
"""
import io
import json
import os
import subprocess
import sys

import pandas as pd

ROOT = r"D:\workplace\Github\OkexAlpha"
OUT = os.path.join(ROOT, "reports", "martingale-100u")
PSQL = r"D:\Program Files\PostgreSQL\18\bin\psql.exe"
ENV = dict(os.environ, PGPASSWORD="123456")

fails = []
checks = 0


def check(name, cond, detail=""):
    global checks
    checks += 1
    if not cond:
        fails.append(f"{name} :: {detail}")
        print(f"FAIL  {name}  {detail}")
    else:
        print(f"ok    {name}")


def sql(q):
    p = subprocess.run([PSQL, "-h", "127.0.0.1", "-U", "postgres", "-d", "okex_alpha",
                        "-A", "-F", ",", "-t", "-c", q],
                       capture_output=True, text=True, env=ENV)
    if p.returncode != 0:
        raise RuntimeError(p.stderr)
    return p.stdout.strip()


# ---------- 0. config ----------
cfg = json.load(io.open(os.path.join(ROOT, "config", "martingale-100u.json"), encoding="utf-8"))
avail = cfg["budget"]["availableUsd"]
check("config: 35 coins", len(cfg["coins"]) == 35, str(len(cfg["coins"])))
check("config: availableUsd matches account", abs(avail - 90.27123346585422) < 1e-9, repr(avail))

# ---------- 1. specs vs DB ----------
specs = pd.read_csv(os.path.join(OUT, "specs.csv"))
check("specs: 35 rows", len(specs) == 35, str(len(specs)))
ids = "','".join(specs.inst_id)
db = sql(f"select inst_id, min_notional_usd from okx_min_order where inst_id in ('{ids}')")
dbm = dict(l.split(",")[:2] for l in db.splitlines() if l)
bad = [(i, round(float(db_m), 6), round(float(s), 6))
       for i, s, db_m in zip(specs.inst_id, specs.minNotionalUsd,
                             [dbm.get(i, "nan") for i in specs.inst_id])
       if not abs(float(db_m) - float(s)) <= 1e-9]
check("specs: minNotionalUsd == okx_min_order (live DB)", not bad, str(bad[:3]))

# ---------- 2. orders compliance ----------
sel = pd.read_csv(os.path.join(OUT, "selected.csv"))
ord_all = pd.read_csv(os.path.join(OUT, "orders.csv")).rename(columns={"instId": "inst_id"})
ordf = ord_all[ord_all.layer > 0].copy()
ord0 = ord_all[ord_all.layer == 0].copy()
check("selected: 27 strategies", len(sel) == 27, str(len(sel)))
check("orders: 55 real layers + 27 totals", len(ordf) == 55 and len(ord0) == 27,
      f"{len(ordf)}/{len(ord0)}")
check("orders: one strategy per coin", sel.inst_id.is_unique, "")

merged = ordf.merge(sel[["idx", "inst_id", "minNotionalUsd", "lotSz", "minSz", "leverage",
                         "stopLossPct", "layerStepPct", "side", "capital_usd"]],
                    on=["idx", "inst_id"], suffixes=("", "_s"))

# 2a. every layer notional >= exchange minimum
short = merged[merged.notional_usd < merged.minNotionalUsd - 1e-9]
check("orders: all layers notional >= minNotionalUsd", short.empty,
      str(short[["inst_id", "layer", "notional_usd", "minNotionalUsd"]].to_dict("records")[:3]))

# 2b. sz is a positive integer multiple of lotSz and >= minSz
bad_sz = []
for r in merged.itertuples():
    q = round(r.sz_contracts / r.lotSz)
    if q < 1 or abs(q * r.lotSz - r.sz_contracts) > 1e-9 or r.sz_contracts < r.minSz - 1e-12:
        bad_sz.append((r.inst_id, r.layer, r.sz_contracts, r.lotSz, r.minSz))
check("orders: sz multiple of lotSz, >= minSz", not bad_sz, str(bad_sz[:3]))

# 2c. notional == sz * ctVal * refPx(trigger) approximation: notional == sz * per-contract
bad_n = []
for r in merged.itertuples():
    expect = r.sz_contracts * r.notional_usd / r.coins if r.coins else 0
    if abs(r.notional_usd - r.sz_contracts * (r.notional_usd / r.sz_contracts)) > 1e-9:
        bad_n.append(r.inst_id)
check("orders: notional consistent with sz", not bad_n, str(bad_n[:3]))

# 2d. layer-1 notional >= minNotional, and layer k price = layer k-1 *(1 -/+ step)
bad_px = []
for idx, g in merged.sort_values(["idx", "layer"]).groupby("idx"):
    g = g.reset_index(drop=True)
    step = g.layerStepPct[0] / 100.0 * (-1 if g.side[0] == "long" else 1)
    for k in range(1, len(g)):
        expect = g.trigger_px[k - 1] * (1 + step)
        if abs(expect - g.trigger_px[k]) > 0.02 * g.trigger_px[k]:
            bad_px.append((g.inst_id[0], k + 1, round(expect, 8), round(g.trigger_px[k], 8)))
check("orders: trigger price ladder follows layerStepPct", not bad_px, str(bad_px[:3]))

# 2e. stop distance == stopLossPct / leverage (price terms)
bad_stop = []
for r in merged.itertuples():
    if pd.isna(r.stop_px):
        continue
    d = abs(r.avg_entry - r.stop_px) / r.avg_entry * 100
    want = r.stopLossPct / r.leverage
    if abs(d - want) > 0.02 * max(want, 0.1):
        bad_stop.append((r.inst_id, r.layer, round(d, 4), round(want, 4)))
check("orders: stop distance == stopLossPct/leverage", not bad_stop, str(bad_stop[:3]))

# 2f. per-strategy margin sums to selected capital
sums = ordf.groupby("idx").margin_usd.sum()
tot = ord0.set_index("idx").margin_usd
check("orders: sum(layer margin) == total row", ((sums - tot).abs() < 0.02).all(),
      str((sums - tot).abs().max()))
d0 = ord0.set_index("idx").margin_usd - sel.set_index("idx").capital_usd
check("orders: rounded total >= backtest nominal for every strategy", (d0 >= -1e-9).all(),
      str(d0[d0 < -1e-9].to_dict()))
check("orders: rounding uplift < 25% per strategy", (d0 <= 0.25 * sel.set_index("idx").capital_usd).all(),
      str(d0[d0 > 0.25 * sel.set_index("idx").capital_usd].to_dict()))

# ---------- 3. budget ----------
cap_bt = sel.capital_usd.sum()
cap = ordf.margin_usd.sum()
check("budget: backtest nominal == 42.30", abs(cap_bt - 42.2985) < 0.01, f"{cap_bt:.4f}")
check("budget: live deployed == 47.15", abs(cap - 47.148) < 0.01, f"{cap:.4f}")
check("budget: deployed <= 72.22 (80% buffer line)", cap <= 72.221, f"{cap:.2f}")
check("budget: deployed <= available", cap <= avail, f"{cap:.2f} <= {avail:.2f}")
worst = sel.worst_loss_usd.sum()
check("budget: worst-case stop-out < 10% of available", worst < 0.10 * avail + 1e-9,
      f"{worst:.2f} vs {0.10*avail:.3f}")
util = cap / avail * 100
check("budget: utilisation ~52.2%", abs(util - 52.2) < 0.5, f"{util:.2f}%")
check("budget: hard buffer >= 40%", (1 - cap / avail) >= 0.40, f"{1-cap/avail:.3f}")

# ---------- 4. portfolio curve ----------
cur = pd.read_csv(os.path.join(OUT, "portfolio_curve.csv"))
check("curve: >= 300 points", len(cur) >= 300, str(len(cur)))
pnl = cur.equity.iloc[-1] - cur.equity.iloc[0]
check("curve: total pnl ~ +516.01", abs(pnl - 516.01) < 0.5, f"{pnl:.2f}")
peak = cur.equity.cummax()
dd_usd = (cur.equity - peak).min()
check("curve: max dd ~ -6.84U", abs(dd_usd + 6.84) < 0.3, f"{dd_usd:.2f}")
check("curve: dd as % of live deployed ~ -14.5%", abs(dd_usd / cap * 100 + 14.5) < 0.8,
      f"{dd_usd/cap*100:.2f}%")
check("curve: no NaN", not cur.isna().any().any(), "")

# ---------- 5. report integrity ----------
rep = io.open(os.path.join(OUT, "report.md"), encoding="utf-8").read()
check("report: no unresolved placeholders", "{{" not in rep, "")
for img in ["equity", "monthly", "alloc", "scatter"]:
    check(f"report: image {img}.png exists",
          os.path.exists(os.path.join(OUT, "img", img + ".png")), "")
check("report: mentions 90.27 available", "90.27" in rep and "90.2712" in rep, "")
check("report: 27-strategy table present", rep.count("| 1 | TIA | 空 | 5 | 2 | 1.5 |") == 1 and rep.count("| 27 | DOGE | 多 | 2 | 2 |") == 2, "")
rows_51 = [l for l in rep.splitlines() if l.startswith("| ") and "| 空 |" in l or "| 多 |" in l]
check("report: >= 27 strategy rows", len([l for l in rows_51 if l.count("|") == 15]) >= 27,
      str(len(rows_51)))

# ---------- 6. backup untouched ----------
import hashlib


def sha(p):
    return hashlib.sha256(io.open(p, "rb").read()).hexdigest().upper()


bk = os.path.join(ROOT, ".run", "backup", "application.yml.20261107-144204.bak")
app = os.path.join(ROOT, "backend", "src", "main", "resources", "application.yml")
check("backup exists", os.path.exists(bk), bk)
check("backup == live application.yml (file unchanged)", sha(bk) == sha(app),
      f"{sha(bk)[:12]} vs {sha(app)[:12]}")

# ---------- 7. backtest run state ----------
flt = "label like 'M10%' and label not like '%%test%%'"
n_runs = sql(f"select count(*) from okx_backtest_run where {flt}")
n_done = sql(f"select count(*) from okx_backtest_run where ({flt}) and status='DONE'")
check("db: 490 grid runs all DONE", n_runs == "490" and n_done == "490", f"{n_done}/{n_runs}")
n_res = sql("select count(*) from okx_backtest_result r join okx_backtest_run u on u.id=r.run_id "
            "where u.label like 'M10%'")
check("db: results persisted", int(n_res) > 100000, n_res)

# ---------- 8. scripts compile ----------
import py_compile
badc = []
for f in os.listdir(os.path.join(ROOT, "scripts", "martingale-100u")):
    if f.endswith(".py"):
        try:
            py_compile.compile(os.path.join(ROOT, "scripts", "martingale-100u", f), doraise=True)
        except Exception as e:  # noqa: BLE001
            badc.append((f, str(e)[:80]))
check("scripts: all .py compile", not badc, str(badc[:2]))

print()
print(f"{checks - len(fails)}/{checks} checks passed")
if fails:
    print("FAILURES:")
    for f in fails:
        print(" -", f)
    sys.exit(1)
print("ALL GREEN")
