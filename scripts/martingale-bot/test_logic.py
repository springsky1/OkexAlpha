# -*- coding: utf-8 -*-
"""
离线自检：用假行情驱动 Bot，验证 开仓 -> 加仓 -> 止盈 / 止损 三条路径与资金口径。

不联网、不下单；断言失败会以非零码退出，便于接 CI。
用"1 张 = 1 币、lot=1"的合成合约，避免 BTC 这种 0.01 张粒度造成的取整噪声。
"""
import shutil
import sys
import tempfile
from pathlib import Path

import martingale as m

SPEC = {"instId": "TEST-USDT-SWAP", "ctVal": 1.0, "ctValCcy": "TEST",
        "lotSz": 0.01, "minSz": 0.01, "tickSz": 0.001, "maxLever": 100, "state": "live"}
P0 = 100.0


class FakeClient:
    def __init__(self, prices):
        self.prices = list(prices)
        self.calls = 0

    def instrument(self, inst_id):
        return SPEC

    def candles(self, inst_id, bar="1H", limit=3):
        px = self.prices[min(self.calls, len(self.prices) - 1)]
        self.calls += 1
        return [{"ts": 1767196800000 + self.calls * 3600_000, "o": px, "h": px, "l": px, "c": px}]


def run(prices, tmp, margin=100.0):
    m.STATE_DIR = Path(tmp)
    p = m.Params(inst_id="TEST-USDT-SWAP", side="long", leverage=3, base_margin_usd=margin,
                 max_layers=2, layer_step_pct=1.2, layer_scale=2.5,
                 take_profit_pct=0.9, stop_loss_pct=20.0)
    return m.Bot(p, FakeClient(prices), dry_run=True)


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS {name}")
    else:
        print(f"  FAIL {name} {detail}")
        sys.exit(1)


def main():
    tmp = tempfile.mkdtemp()
    try:
        # 场景 1：开仓 -> 逆势 1.3% 加第 2 层 -> 反弹 0.9% 止盈
        bot = run([P0, 98.7, 99.6], tmp)
        bot.tick()
        check("open layer1", len(bot.state.layers) == 1)
        n1 = bot.state.layers[0].notional
        check("layer1 notional ~ margin x lev", abs(n1 - 300.0) < 6.0, f"{n1:.2f}")
        bot.tick()
        check("add layer2", len(bot.state.layers) == 2)
        n2 = bot.state.layers[1].notional
        check("layer2 = layer1 x 2.5", abs(n2 / n1 - 2.5) < 0.08, f"{n2:.2f}/{n1:.2f}")
        avg = bot.state.avg()
        check("avg between layer1 and layer2", 98.7 < avg < P0, f"{avg:.4f}")
        bot.c.prices = [avg * 1.0095]
        bot.tick()
        check("tp closes all", len(bot.state.layers) == 0)
        check("tp realized > 0", bot.state.realized > 0, f"{bot.state.realized:.3f}")

        # 场景 2：开仓后直接打穿止损（3x + 20% 保证金 => 6.67% 价格）
        shutil.rmtree(tmp, ignore_errors=True); Path(tmp).mkdir(parents=True)
        bot = run([P0, 93.0], tmp)
        bot.tick()
        check("open layer1 (s2)", len(bot.state.layers) == 1)
        bot.tick()
        check("sl closes all", len(bot.state.layers) == 0)
        check("sl realized < 0", bot.state.realized < 0, f"{bot.state.realized:.3f}")
        loss_pct = bot.state.realized / 100.0
        check("sl loss 10%-30% of margin", -0.35 < loss_pct < -0.10, f"{loss_pct*100:.1f}%")

        # 场景 3：满层保证金 = 首层 x (1 + scale) = 3.5x
        shutil.rmtree(tmp, ignore_errors=True); Path(tmp).mkdir(parents=True)
        bot = run([P0, 98.7], tmp)
        bot.tick(); bot.tick()
        ratio = bot.state.margin() / 100.0
        check("max margin = 3.5x base", abs(ratio - 3.5) < 0.05, f"{ratio:.3f}")

        # 场景 4：没到加仓间距就不加层（1.0% < 1.2%）
        shutil.rmtree(tmp, ignore_errors=True); Path(tmp).mkdir(parents=True)
        bot = run([P0, 99.0], tmp)
        bot.tick(); bot.tick()
        check("no add below step", len(bot.state.layers) == 1)

        # 场景 5：空单方向（价格上涨 1.3% 加仓，下跌 0.9% 止盈）
        shutil.rmtree(tmp, ignore_errors=True); Path(tmp).mkdir(parents=True)
        m.STATE_DIR = Path(tmp)
        p = m.Params(inst_id="TEST-USDT-SWAP", side="short", leverage=3, base_margin_usd=100.0)
        bot = m.Bot(p, FakeClient([P0, 101.4, 99.9]), dry_run=True)
        bot.tick(); bot.tick()
        check("short adds on rally", len(bot.state.layers) == 2)
        bot.tick()
        check("short tp closes", len(bot.state.layers) == 0 and bot.state.realized > 0,
              f"{bot.state.realized:.3f}")

        print("ALL LOGIC CHECKS PASSED")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
