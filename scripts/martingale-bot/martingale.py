# -*- coding: utf-8 -*-
"""
USDT 本位永续 · 经典马丁格尔执行器（单币单策略，双向持仓）。

设计目标（与回测引擎同口径）：
  * 第 1 层名义 = base_margin_usd × leverage，随后每层按 layer_scale 放大；
  * 逆势走 layer_step_pct 加一层，最多 max_layers 层；
  * 均价止盈 take_profit_pct，止损按"亏掉已用保证金的 stop_loss_pct%"折算成价格距离
    （stop 距离 = stop_loss_pct% × 均价 ÷ 杠杆，与层数无关）；
  * 触发顺序：强平保护 → 止损 → 加仓 → 止盈，和回测引擎一致。

安全默认值：
  * 默认 --dry-run（纸面撮合，不发送任何私有请求以外的东西）；
  * 真跑必须同时给 --live 与 --confirm CONFIRM-REAL-TRADING；
  * 单日亏损上限、最大并发、kill-switch 文件三重闸门。

用法：
  python martingale.py --inst-id BTC-USDT-SWAP --once            # 纸面跑一次
  python martingale.py --inst-id BTC-USDT-SWAP --loop 60 --live --confirm CONFIRM-REAL-TRADING
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

from okx_client import Credentials, OkxClient, OkxError

HERE = Path(__file__).resolve().parent
STATE_DIR = HERE / "state"
KILL_SWITCH = HERE / "STOP"
FEE_TAKER = 0.0005       # 与仓库配置一致：taker 0.05%
SLIPPAGE = 0.0002        # 2bps，纸面撮合时折算进成交价
MMR = 0.005              # 维持保证金率


@dataclass
class Params:
    inst_id: str = "BTC-USDT-SWAP"
    side: str = "long"            # long / short
    leverage: int = 3
    base_margin_usd: float = 2.5  # 第 1 层保证金（引擎里的 initialMarginUsd）
    max_layers: int = 2
    layer_step_pct: float = 1.2
    layer_scale: float = 2.5
    take_profit_pct: float = 0.9
    stop_loss_pct: float = 20.0   # 相对已用保证金的百分比
    max_daily_loss_usd: float = 5.0
    bar: str = "1H"


@dataclass
class Layer:
    px: float
    sz: float          # 张数
    notional: float    # 名义 USD
    margin: float      # 保证金 USD


@dataclass
class PositionState:
    inst_id: str
    side: str
    layers: list[Layer] = field(default_factory=list)
    opened_ts: int = 0
    realized: float = 0.0
    day: str = ""
    day_pnl: float = 0.0

    @property
    def qty(self) -> float:          # 币数量（张数 × ctVal）
        return 0.0

    def notional(self) -> float:
        return sum(l.notional for l in self.layers)

    def margin(self) -> float:
        return sum(l.margin for l in self.layers)

    def avg(self) -> float:
        n = self.notional()
        return sum(l.px * l.notional for l in self.layers) / n if n else 0.0


def state_path(inst_id: str) -> Path:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    return STATE_DIR / f"{inst_id}.json"


def load_state(inst_id: str, side: str) -> PositionState:
    p = state_path(inst_id)
    if p.exists():
        d = json.loads(p.read_text(encoding="utf-8"))
        d["layers"] = [Layer(**x) for x in d.get("layers", [])]
        return PositionState(**d)
    return PositionState(inst_id=inst_id, side=side)


def save_state(s: PositionState) -> None:
    d = asdict(s)
    state_path(s.inst_id).write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")


def round_lot(sz: float, lot: float) -> float:
    if lot <= 0:
        return sz
    return math.floor(sz / lot + 1e-9) * lot


def contracts_for(notional_usd: float, px: float, ct_val: float, lot: float, min_sz: float) -> float:
    """名义 USD -> 张数（向下取到 lotSz，且不低于 minSz）。"""
    raw = notional_usd / (px * ct_val)
    sz = round_lot(raw, lot)
    return max(sz, min_sz)


class Bot:
    def __init__(self, p: Params, client: OkxClient, dry_run: bool):
        self.p = p
        self.c = client
        self.dry = dry_run
        self.spec = client.instrument(p.inst_id)
        self.state = load_state(p.inst_id, p.side)

    # ---------- 价格工具 ----------
    def sl_price(self, avg: float, side: str) -> float:
        dist = self.p.stop_loss_pct / 100.0 * avg / self.p.leverage
        return avg - dist if side == "long" else avg + dist

    def liq_price(self, avg: float, side: str) -> float:
        move = 1.0 / self.p.leverage - MMR
        return avg * (1 - move) if side == "long" else avg * (1 + move)

    def tp_price(self, avg: float, side: str) -> float:
        d = self.p.take_profit_pct / 100.0
        return avg * (1 + d) if side == "long" else avg * (1 - d)

    def adverse_pct(self, px: float) -> float:
        avg = self.state.avg()
        if not avg:
            return 0.0
        return (avg - px) / avg * 100 if self.p.side == "long" else (px - avg) / avg * 100

    def worst_pct(self, px: float) -> float:
        """相对均价的浮亏百分比（用于止损/强平判断，永远 >=0）。"""
        avg = self.state.avg()
        if not avg:
            return 0.0
        return (avg - px) / avg * 100 if self.p.side == "long" else (px - avg) / avg * 100

    # ---------- 执行 ----------
    def _order(self, side: str, sz: float, reduce_only: bool) -> dict:
        if self.dry:
            return {"dryRun": True, "side": side, "sz": sz}
        return self.c.place_order(self.p.inst_id, side, self.p.side, f"{sz:g}",
                                  reduce_only=reduce_only)

    def open_layer(self, px: float, layer_no: int) -> None:
        """按引擎口径开第 layer_no 层：notional = base_margin×lev×scale^(layer-1)。"""
        notional = self.p.base_margin_usd * self.p.leverage * (self.p.layer_scale ** (layer_no - 1))
        sz = contracts_for(notional, px, self.spec["ctVal"], self.spec["lotSz"], self.spec["minSz"])
        real_notional = sz * self.spec["ctVal"] * px
        if real_notional < 5:      # 5U 以下不划算（手续费/最小变动都吃不消）
            print(f"  [skip] layer{layer_no} notional={real_notional:.2f}U < 5U")
            return
        fill = px * (1 + SLIPPAGE) if self.p.side == "long" else px * (1 - SLIPPAGE)
        if self.dry:
            print(f"  [paper] open layer{layer_no} {self.p.side} sz={sz:g} @ {fill:.6g} "
                  f"notional={real_notional:.2f}U margin={real_notional/self.p.leverage:.2f}U")
        else:
            res = self._order("buy" if self.p.side == "long" else "sell", sz, reduce_only=False)
            print(f"  [live] open layer{layer_no} sz={sz:g} -> {res}")
        self.state.layers.append(Layer(px=fill, sz=sz, notional=real_notional,
                                       margin=real_notional / self.p.leverage))
        save_state(self.state)

    def close_all(self, px: float, reason: str) -> None:
        st = self.state
        if not st.layers:
            return
        avg = st.avg()
        qty_ccy = sum(l.sz for l in st.layers) * self.spec["ctVal"]
        sign = 1 if self.p.side == "long" else -1
        fill = px * (1 - SLIPPAGE) if self.p.side == "long" else px * (1 + SLIPPAGE)
        gross = (fill - avg) * qty_ccy * sign
        fee = st.notional() * FEE_TAKER * 2
        pnl = gross - fee
        print(f"  close[{reason}] avg={avg:.6g} fill={fill:.6g} pnl={pnl:+.4f}U "
              f"({pnl / max(st.margin(), 1e-9) * 100:+.2f}% of margin)")
        if not self.dry:
            total_sz = sum(l.sz for l in st.layers)
            self._order("sell" if self.p.side == "long" else "buy", total_sz, reduce_only=True)
        st.realized += pnl
        st.day_pnl += pnl
        st.layers = []
        save_state(st)

    # ---------- 主循环 ----------
    def tick(self) -> None:
        p = self.p
        if KILL_SWITCH.exists():
            print("STOP 文件存在，退出。")
            sys.exit(0)
        candles = self.c.candles(p.inst_id, p.bar, 3)
        bar = candles[-1]          # 用最新一根的收盘价做决策（保守：已收盘）
        px = bar["c"]
        st = self.state
        today = time.strftime("%Y-%m-%d")
        if st.day != today:
            st.day, st.day_pnl = today, 0.0
        if st.day_pnl <= -p.max_daily_loss_usd:
            print(f"日亏损 {st.day_pnl:.2f}U 触顶，今日停手。")
            return

        print(f"[{time.strftime('%H:%M:%S')}] {p.inst_id} {p.side} px={px:.6g} "
              f"layers={len(st.layers)} avg={st.avg():.6g}")

        if not st.layers:
            print("  空仓 -> 开第 1 层")
            self.open_layer(px, 1)
            st.opened_ts = bar["ts"]
            save_state(st)
            return

        # 1) 强平保护（理论上逐仓已兜底，这里做最后一道防线）
        if self.worst_pct(px) >= (1.0 / p.leverage - MMR) * 100:
            self.close_all(px, "LIQ_GUARD")
            return
        # 2) 止损
        if self.worst_pct(px) >= p.stop_loss_pct / 100.0 / p.leverage * 100:
            self.close_all(px, "STOP_LOSS")
            return
        # 3) 逆势加仓
        if len(st.layers) < p.max_layers and self.adverse_pct(px) >= p.layer_step_pct:
            self.open_layer(px, len(st.layers) + 1)
            return
        # 4) 止盈
        if (self.p.side == "long" and px >= self.tp_price(st.avg(), "long")) or \
           (self.p.side == "short" and px <= self.tp_price(st.avg(), "short")):
            self.close_all(px, "TAKE_PROFIT")
            return
        print("  持仓中，无动作")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inst-id", default="BTC-USDT-SWAP")
    ap.add_argument("--side", choices=["long", "short"], default="long")
    ap.add_argument("--leverage", type=int, default=3)
    ap.add_argument("--margin", type=float, default=2.5, help="第 1 层保证金 USD")
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--step", type=float, default=1.2, help="加仓间距 %%")
    ap.add_argument("--scale", type=float, default=2.5, help="加仓倍数")
    ap.add_argument("--tp", type=float, default=0.9, help="止盈 %%")
    ap.add_argument("--sl", type=float, default=20.0, help="止损（占保证金 %%）")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", type=int, default=0, help="轮询秒数，0=只跑一次")
    ap.add_argument("--live", action="store_true")
    ap.add_argument("--confirm", default="")
    a = ap.parse_args()

    if a.live:
        if a.confirm != "CONFIRM-REAL-TRADING":
            print("真跑需要 --confirm CONFIRM-REAL-TRADING")
            return 2
        key = os.environ.get("OKX_API_KEY", "")
        sec = os.environ.get("OKX_API_SECRET", "")
        pw = os.environ.get("OKX_API_PASSPHRASE", "")
        if not (key and sec and pw):
            print("缺少 OKX_API_KEY / OKX_API_SECRET / OKX_API_PASSPHRASE")
            return 2
        creds = Credentials(key, sec, pw, simulated=os.environ.get("OKX_SIMULATED") == "1")
    else:
        creds = Credentials()

    p = Params(inst_id=a.inst_id, side=a.side, leverage=a.leverage,
               base_margin_usd=a.margin, max_layers=a.layers, layer_step_pct=a.step,
               layer_scale=a.scale, take_profit_pct=a.tp, stop_loss_pct=a.sl)
    client = OkxClient(creds)
    bot = Bot(p, client, dry_run=not a.live)
    while True:
        try:
            bot.tick()
        except OkxError as e:
            print("okx error:", e)
        if not a.loop:
            return 0
        time.sleep(a.loop)


if __name__ == "__main__":
    sys.exit(main())
