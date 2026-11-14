#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
manager.py —— 把已成交的裸仓接管为「马丁格尔托管仓」。

持仓以交易所为准（GET /api/v1/account/positions），参数取 reports/martingale-100u/selected.csv，
下单走本地服务 POST /api/v1/strategy/live/orders（复用门禁、posSide 与 REAL 确认口令），
本脚本不接触 API 密钥。

每个 tick 的处理顺序（与回测引擎一致）：
  强平保护 LIQ_GUARD → 止损 SL → 加仓 ADD → 止盈 TP → 超时 TIMEOUT

用法：
  python manager.py --adopt              # 首次：按当前持仓登记 layer1 名义与层数
  python manager.py --once               # 干跑一轮，打印每仓的 tp/sl/加仓触发线
  python manager.py --live --once        # 真实执行一轮
  python manager.py --live --loop 60     # 每 60 秒一轮常驻
  touch .run/bot/STOP                    # 熔断：所有动作停止
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

API = "http://127.0.0.1:18080"
ROOT = Path(__file__).resolve().parents[2]
SELECTED = ROOT / "reports" / "martingale-100u" / "selected.csv"
RUN_DIR = ROOT / ".run" / "bot"
STATE = RUN_DIR / "state.json"
STOP_FILE = RUN_DIR / "STOP"
LOG = RUN_DIR / "manager.log"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0 Safari/537.36")
MMR = 0.005
CONFIRM = "CONFIRM-REAL-TRADING"
MAX_ADD_LAYERS = 2          # 本轮只托管到第 2 层（与方案一致）
MIN_LAYER_NOTIONAL = 0.5    # 单层名义 < 0.5U 不开（低于此手续费不划算）
MAX_OPENS_PER_DAY = 12      # 每币每日最多重新开仓次数（防止震荡行情里疯狂循环）
REOPEN_COOLDOWN_S = 60      # 平仓后至少冷却 60s 再重开
GLOBAL_MIN_AVAIL = 20.0     # 可用 < 20U 时不再开新仓 / 加仓


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
    max_hold_hours: int = 48


def log(msg: str) -> None:
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def load_params() -> dict[str, P]:
    out: dict[str, P] = {}
    with open(SELECTED, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            out[r["inst_id"]] = P(
                inst_id=r["inst_id"], side=r["side"],
                leverage=int(float(r["leverage"])),
                max_layers=int(float(r["maxLayers"])),
                layer_scale=float(r["layerScale"]),
                layer_step_pct=float(r["layerStepPct"]),
                take_profit_pct=float(r["takeProfitPct"]),
                stop_loss_pct=float(r["stopLossPct"]),
            )
    return out


def http_json(url: str, body: dict | None = None, timeout: float = 120.0) -> dict:
    headers = {"Content-Type": "application/json", "User-Agent": UA}
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except Exception:
            return {"code": str(e.code), "msg": raw[:300]}


def positions() -> list[dict]:
    r = http_json(f"{API}/api/v1/account/positions")
    if str(r.get("code")) != "0":
        raise RuntimeError(f"positions failed: {r}")
    return r["data"]["items"]


def balance() -> tuple[float, float]:
    """返回 (可用 USDT, 总权益 USDT)。"""
    r = http_json(f"{API}/api/v1/account/balance?ccy=USDT")
    d = r.get("data") or {}
    avail = 0.0
    for x in d.get("details") or []:
        if x.get("ccy") == "USDT":
            avail = float(x.get("availBal") or 0)
            break
    return avail, float(d.get("totalEq") or 0)


def avail_usdt() -> float:
    return balance()[0]


def marks() -> dict[str, float]:
    r = http_json("https://www.okx.com/api/v5/market/tickers?instType=SWAP", timeout=20)
    return {t["instId"]: float(t["last"]) for t in r.get("data", [])}


def place(inst: str, side: str, pos_side: str, sz: str, reduce_only: bool) -> dict:
    body = {"instId": inst, "side": side, "posSide": pos_side, "sz": sz,
            "ordType": "market", "tdMode": "isolated",
            "reduceOnly": reduce_only, "confirm": CONFIRM}
    return http_json(f"{API}/api/v1/strategy/live/orders", body, timeout=180)


def fmt(sz: float) -> str:
    s = f"{sz:.10f}".rstrip("0").rstrip(".")
    return s if s else "0"


def price_lines(p: P, avg: float) -> dict:
    dist = p.stop_loss_pct / 100.0 * avg / p.leverage
    tp_d = p.take_profit_pct / 100.0
    liq_d = 1.0 / p.leverage - MMR
    if p.side == "long":
        return {"tp": avg * (1 + tp_d), "sl": avg - dist, "liq": avg * (1 - liq_d)}
    return {"tp": avg * (1 - tp_d), "sl": avg + dist, "liq": avg * (1 + liq_d)}


def adverse(p: P, avg: float, px: float) -> float:
    return (avg - px) / avg * 100 if p.side == "long" else (px - avg) / avg * 100


def size_for(notional: float, px: float, ct_val: float, lot: float, min_sz: float) -> float:
    raw = notional / (px * ct_val)
    if lot > 0:
        raw = math.floor(raw / lot + 1e-9) * lot
    return max(raw, min_sz)


def load_state() -> dict:
    if STATE.exists():
        return json.loads(STATE.read_text(encoding="utf-8"))
    return {}


def save_state(st: dict) -> None:
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=2), encoding="utf-8")


def adopt(params: dict[str, P]) -> int:
    """按当前持仓登记：layer1_notional = 当前名义，layers = 1。"""
    st = load_state()
    n = 0
    for it in positions():
        inst = it["instId"]
        if inst not in params:
            continue
        st[inst] = {"layer1_notional": float(it["notionalUsd"]), "layers": 1,
                    "opened_ts": int(it["cTime"])}
        n += 1
    save_state(st)
    log(f"adopt: {n} 个持仓已登记")
    return n


def run_once(live: bool, params: dict[str, P], reentry: bool = True) -> int:
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    if STOP_FILE.exists():
        log(f"[stop] STOP 文件存在，跳过本轮（删除 {STOP_FILE.name} 恢复）")
        return 0
    pos = [it for it in positions() if it["instId"] in params]
    pxmap = marks()
    st = load_state()
    now = int(time.time() * 1000)
    avail, eq = balance()
    # 日亏损闸门：当日权益相对当日首见值回撤 > 4.5U 即熔断
    today = datetime.now().strftime("%Y-%m-%d")
    day = st.get("_day") or {}
    if day.get("date") != today:
        day = {"date": today, "eq": eq}
        st["_day"] = day
    elif eq and day.get("eq") and eq < day["eq"] - 4.5:
        STOP_FILE.write_text(f"daily loss {day['eq'] - eq:.2f}U on {today}", encoding="utf-8")
        log(f"[guard] 日亏损 {day['eq'] - eq:.2f}U 超过 4.5U，已写 STOP 熔断")
    log(f"tick: {len(pos)} 仓  live={live}  avail={avail:.2f}U  eq={eq:.2f}U  reentry={reentry}")
    dirty = False
    acts = 0
    for it in pos:
        inst = it["instId"]
        p = params[inst]
        side = it["posSide"]
        avg = float(it["avgPx"])
        pos_sz = float(it["pos"])
        mark = pxmap.get(inst, float(it.get("markPx") or 0))
        if mark <= 0:
            continue
        ln = price_lines(p, avg)
        adv = adverse(p, avg, mark)
        held_h = (now - int(it["cTime"])) / 3_600_000
        rec = st.get(inst) or {"layer1_notional": float(it["notionalUsd"]), "layers": 1,
                               "opened_ts": int(it["cTime"])}
        st[inst] = rec
        dirty = True
        print(f"{inst:<18} {side:<5} sz={fmt(pos_sz):<8} avg={avg:<10.6g} mark={mark:<10.6g} "
              f"tp={ln['tp']:<10.6g} sl={ln['sl']:<10.6g} liq={ln['liq']:<10.6g} "
              f"adv={adv:+6.2f}% held={held_h:5.1f}h L{rec['layers']}/{p.max_layers}", flush=True)

        # 1) 强平保护
        if (p.side == "long" and mark <= ln["liq"] * 1.01) or (p.side == "short" and mark >= ln["liq"] * 0.99):
            acts += do_close(inst, side, pos_sz, "LIQ_GUARD", live, rec, now)
            continue
        # 2) 止损
        if (mark <= ln["sl"]) if p.side == "long" else (mark >= ln["sl"]):
            acts += do_close(inst, side, pos_sz, "SL", live, rec, now)
            continue
        # 3) 加仓
        if adv >= p.layer_step_pct and rec["layers"] < min(p.max_layers, MAX_ADD_LAYERS):
            if avail >= GLOBAL_MIN_AVAIL:
                acts += do_add(inst, side, mark, p, rec, live)
            continue
        # 4) 止盈
        if (mark >= ln["tp"]) if p.side == "long" else (mark <= ln["tp"]):
            acts += do_close(inst, side, pos_sz, "TP", live, rec, now)
            continue
        # 5) 超时
        if held_h >= p.max_hold_hours:
            acts += do_close(inst, side, pos_sz, "TIMEOUT", live, rec, now)

    # 6) 空仓重开（ALWAYS_IN，与回测引擎一致）
    if reentry and avail >= GLOBAL_MIN_AVAIL:
        held = {it["instId"] for it in pos}
        today = datetime.now().strftime("%Y-%m-%d")
        for inst, p in params.items():
            if inst in held:
                continue
            rec = st.get(inst)
            if not rec:
                continue
            if rec.get("day") != today:
                rec["day"] = today
                rec["opens"] = 0
            if rec.get("opens", 0) >= MAX_OPENS_PER_DAY:
                continue
            if now - int(rec.get("last_close_ts") or 0) < REOPEN_COOLDOWN_S * 1000:
                continue
            mark = pxmap.get(inst)
            if not mark:
                continue
            acts += do_open(inst, p, mark, rec, live, now)
            avail -= 2.5
            if avail < GLOBAL_MIN_AVAIL:
                log(f"  [guard] 可用余量 {avail:.2f}U 不足，停止本轮重开")
                break
    if dirty:
        save_state(st)
    log(f"tick done: actions={acts}")
    return acts


def do_close(inst: str, pos_side: str, sz: float, reason: str, live: bool,
             rec: dict | None = None, now: int = 0) -> int:
    side = "sell" if pos_side == "long" else "buy"
    log(f"  -> [{reason}] close {inst} {pos_side} sz={fmt(sz)}")
    if not live:
        return 1
    r = place(inst, side, pos_side, fmt(sz), reduce_only=True)
    ok = str(r.get("code")) == "0"
    log(f"     result code={r.get('code')} msg={r.get('msg') or ''}")
    if ok and rec is not None:
        rec["last_close_ts"] = now
        rec["layers"] = 0
        rec["opened_ts"] = 0
        save_state(load_state() | {inst: rec})
    return 1


def do_open(inst: str, p: P, mark: float, rec: dict, live: bool, now: int) -> int:
    notional = float(rec.get("layer1_notional") or 0)
    if notional < MIN_LAYER_NOTIONAL:
        return 0
    spec = http_json(f"{API}/api/v1/instruments/{inst}").get("data") or {}
    ct_val = float(spec.get("ctVal") or 1)
    lot = float(spec.get("lotSz") or 0)
    min_sz = float(spec.get("minSz") or 0)
    sz = size_for(notional, mark, ct_val, lot, min_sz)
    side = "buy" if p.side == "long" else "sell"
    log(f"  -> [OPEN] {inst} {p.side} layer1 notional={notional:.2f}U sz={fmt(sz)} mark={mark:.6g}")
    if not live:
        return 1
    r = place(inst, side, p.side, fmt(sz), reduce_only=False)
    ok = str(r.get("code")) == "0"
    log(f"     result code={r.get('code')} msg={r.get('msg') or ''}")
    if ok:
        rec["layers"] = 1
        rec["opened_ts"] = now
        rec["opens"] = int(rec.get("opens") or 0) + 1
        save_state(load_state() | {inst: rec})
    return 1


def do_add(inst: str, pos_side: str, mark: float, p: P, rec: dict, live: bool) -> int:
    layer = rec["layers"] + 1
    notional = rec["layer1_notional"] * (p.layer_scale ** (layer - 1))
    if notional < MIN_LAYER_NOTIONAL:
        log(f"  [skip] {inst} layer{layer} notional={notional:.2f}U < {MIN_LAYER_NOTIONAL}U")
        return 0
    spec = http_json(f"{API}/api/v1/instruments/{inst}").get("data") or {}
    ct_val = float(spec.get("ctVal") or 1)
    lot = float(spec.get("lotSz") or 0)
    min_sz = float(spec.get("minSz") or 0)
    sz = size_for(notional, mark, ct_val, lot, min_sz)
    side = "buy" if pos_side == "long" else "sell"
    log(f"  -> [ADD] {inst} {pos_side} layer{layer} notional={notional:.2f}U sz={fmt(sz)} mark={mark:.6g}")
    if not live:
        return 1
    r = place(inst, side, pos_side, fmt(sz), reduce_only=False)
    ok = str(r.get("code")) == "0"
    log(f"     result code={r.get('code')} msg={r.get('msg') or ''}")
    if ok:
        rec["layers"] = layer
        rec["layer1_notional"] = rec["layer1_notional"]
        save_state(load_state() | {inst: rec})
    return 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="真实下单（默认干跑）")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--adopt", action="store_true")
    ap.add_argument("--loop", type=int, default=0, help="循环间隔秒数")
    ap.add_argument("--no-reentry", action="store_true", help="平仓后不自动重开")
    a = ap.parse_args()
    params = load_params()
    if a.adopt:
        adopt(params)
        return 0
    if a.loop:
        RUN_DIR.mkdir(parents=True, exist_ok=True)
        (RUN_DIR / "manager.pid").write_text(str(os.getpid()), encoding="utf-8")
    reentry = not a.no_reentry
    if a.once or not a.loop:
        run_once(a.live, params, reentry)
        return 0
    while True:
        try:
            run_once(a.live, params, reentry)
        except Exception as e:
            log(f"[error] {type(e).__name__}: {e}")
        time.sleep(a.loop)


if __name__ == "__main__":
    sys.exit(main())
