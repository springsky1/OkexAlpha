#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
paper.py —— 批量永续马丁「纸面机器人」：真实行情、模拟成交、不下单。

用途：把新一轮寻优选出的 N 组参数，用最新最小下单金额（coin 当前价对应的 minNotionalUsd）
跑成一批纸面机器人，观察它们在实盘行情下的行为（开层/加层/止盈/止损/超时）。

参数文件：reports/martingale-100u/selected2.csv
状态与日志：.run/paper/state.json、.run/paper/paper.log

用法：
  python paper.py --once          # 跑一轮并打印状态
  python paper.py --loop 30       # 每 30 秒一轮
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEL = ROOT / "reports" / "martingale-100u" / "selected2.csv"
SPECS = ROOT / "reports" / "martingale-100u" / "specs2.csv"
RUN = ROOT / ".run" / "paper"
STATE = RUN / "state.json"
LOG = RUN / "paper.log"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Safari/537.36")
FEE = 0.0005          # taker 单边
SLIP = 0.0002         # 2bps
MMR = 0.005
MAX_HOLD_H = 48


@dataclass
class P:
    inst_id: str
    side: str
    leverage: int
    max_layers: int
    layer_scale: float
    layer_step_pct: float
    take_profit_pct: float
    stop_loss_pct: float
    layer1_notional: float
    ct_val: float = 1.0
    lot: float = 1.0
    min_sz: float = 1.0


@dataclass
class S:
    layers: list = field(default_factory=list)   # [{px, sz, notional}]
    realized: float = 0.0
    trades: int = 0
    wins: int = 0
    opened_ts: int = 0
    peak_margin: float = 0.0

    @property
    def notional(self) -> float:
        return sum(x["notional"] for x in self.layers)

    @property
    def avg(self) -> float:
        n = self.notional
        return sum(x["px"] * x["notional"] for x in self.layers) / n if n else 0.0


def log(msg: str) -> None:
    RUN.mkdir(parents=True, exist_ok=True)
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def http_json(url: str, timeout: float = 20.0) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def load_params() -> list[P]:
    specs = {}
    if SPECS.exists():
        with open(SPECS, encoding="utf-8-sig", newline="") as fh:
            for r in csv.DictReader(fh):
                specs[r["inst_id"]] = (float(r["ctVal"]), float(r["lotSz"]), float(r["minSz"]))
    out = []
    with open(SEL, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            ct, lot, msz = specs.get(r["inst_id"], (1.0, 1.0, 1.0))
            out.append(P(
                inst_id=r["inst_id"], side=r["side"],
                leverage=int(float(r["leverage"])),
                max_layers=int(float(r["maxLayers"])),
                layer_scale=float(r["layerScale"]),
                layer_step_pct=float(r["layerStepPct"]),
                take_profit_pct=float(r["takeProfitPct"]),
                stop_loss_pct=float(r["stopLossPct"]),
                layer1_notional=float(r["layer1_notional_usd"]),
                ct_val=ct, lot=lot, min_sz=msz,
            ))
    return out


def marks() -> dict[str, float]:
    """OKX tickers via curl.exe: python urllib 会被 OKX WAF 直接断连（RemoteDisconnected）。"""
    RUN.mkdir(parents=True, exist_ok=True)
    tmp = RUN / "tickers.json"
    subprocess.run(["curl.exe", "-s", "--max-time", "30",
                    "https://www.okx.com/api/v5/market/tickers?instType=SWAP",
                    "-o", str(tmp)], check=True)
    r = json.loads(tmp.read_text(encoding="utf-8"))
    return {t["instId"]: float(t["last"]) for t in r.get("data", [])}


def round_lot(sz: float, lot: float) -> float:
    if lot <= 0:
        return sz
    return math.floor(sz / lot + 1e-9) * lot


def contracts(p: P, notional: float, px: float) -> float:
    sz = round_lot(notional / (px * p.ct_val), p.lot)
    return max(sz, p.min_sz)


def price_lines(p: P, avg: float) -> dict:
    dist = p.stop_loss_pct / 100.0 * avg / p.leverage
    tp = p.take_profit_pct / 100.0
    if p.side == "long":
        return {"tp": avg * (1 + tp), "sl": avg - dist}
    return {"tp": avg * (1 - tp), "sl": avg + dist}


def adverse(p: P, avg: float, px: float) -> float:
    return (avg - px) / avg * 100 if p.side == "long" else (px - avg) / avg * 100


def open_layer(st: S, p: P, px: float, layer_no: int, now: int) -> None:
    notional = p.layer1_notional * (p.layer_scale ** (layer_no - 1))
    sz = contracts(p, notional, px)
    real = sz * p.ct_val * px
    st.layers.append({"px": px, "sz": sz, "notional": real})
    if layer_no == 1:
        st.opened_ts = now
    st.peak_margin = max(st.peak_margin, st.notional / p.leverage)


def close_all(st: S, p: P, px: float, reason: str) -> None:
    avg = st.avg
    qty = sum(x["sz"] for x in st.layers) * p.ct_val
    gross = (px - avg) * qty if p.side == "long" else (avg - px) * qty
    fees = st.notional * FEE * 2 + st.notional * SLIP
    pnl = gross - fees
    st.realized += pnl
    st.trades += 1
    if pnl > 0:
        st.wins += 1
    log(f"  * [{reason}] {p.inst_id} {p.side} avg={avg:.6g} px={px:.6g} "
        f"notional={st.notional:.3f}U pnl={pnl:+.4f}U cum={st.realized:+.4f}U")
    st.layers = []
    st.opened_ts = 0


def run_once(params: list[P]) -> None:
    RUN.mkdir(parents=True, exist_ok=True)
    pxmap = marks()
    st_all = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
    now = int(time.time() * 1000)
    acted = 0
    for p in params:
        px = pxmap.get(p.inst_id)
        if not px:
            continue
        raw = st_all.get(p.inst_id) or {}
        st = S(layers=raw.get("layers", []), realized=raw.get("realized", 0.0),
               trades=raw.get("trades", 0), wins=raw.get("wins", 0),
               opened_ts=raw.get("opened_ts", 0), peak_margin=raw.get("peak_margin", 0.0))
        if not st.layers:
            open_layer(st, p, px, 1, now)
            acted += 1
            log(f"  -> [OPEN] {p.inst_id} {p.side} px={px:.6g} notional={st.notional:.3f}U")
        else:
            avg = st.avg
            ln = price_lines(p, avg)
            adv = adverse(p, avg, px)
            held_h = (now - st.opened_ts) / 3_600_000 if st.opened_ts else 0
            if (px <= ln["sl"]) if p.side == "long" else (px >= ln["sl"]):
                close_all(st, p, px, "SL"); acted += 1
            elif adv >= p.layer_step_pct and len(st.layers) < p.max_layers:
                open_layer(st, p, px, len(st.layers) + 1, now)
                acted += 1
                log(f"  -> [ADD] {p.inst_id} layer{len(st.layers)} px={px:.6g} notional={st.notional:.3f}U")
            elif (px >= ln["tp"]) if p.side == "long" else (px <= ln["tp"]):
                close_all(st, p, px, "TP"); acted += 1
            elif held_h >= MAX_HOLD_H:
                close_all(st, p, px, "TIMEOUT"); acted += 1
        st_all[p.inst_id] = {"layers": st.layers, "realized": st.realized, "trades": st.trades,
                             "wins": st.wins, "opened_ts": st.opened_ts, "peak_margin": st.peak_margin}
    STATE.write_text(json.dumps(st_all, ensure_ascii=False, indent=2), encoding="utf-8")

    # 汇总
    tot_real = sum(v["realized"] for v in st_all.values())
    tot_peak = sum(v.get("peak_margin", 0) for v in st_all.values())
    opened = sum(1 for v in st_all.values() if v["layers"])
    trades = sum(v["trades"] for v in st_all.values())
    wins = sum(v["wins"] for v in st_all.values())
    log(f"paper tick: strategies={len(params)} open={opened} trades={trades} "
        f"win={wins} realized={tot_real:+.4f}U peakMargin={tot_peak:.3f}U actions={acted}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", type=int, default=0)
    a = ap.parse_args()
    params = load_params()
    if a.once or not a.loop:
        run_once(params)
        return 0
    while True:
        try:
            run_once(params)
        except Exception as e:
            log(f"[error] {type(e).__name__}: {e}")
        time.sleep(a.loop)


if __name__ == "__main__":
    sys.exit(main())
