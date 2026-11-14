#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Generate the wave-3 order table (layer 1 + layer 2) from selected2.csv / specs2.csv."""
import csv
import json
import math
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "reports", "martingale-100u")


def rd(name):
    with open(os.path.join(OUT, name), encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh))


def main():
    sel = rd("selected2.csv")
    spec = {r["inst_id"]: r for r in rd("specs2.csv")}
    rows = []
    for s in sel:
        sp = spec[s["inst_id"]]
        px = float(sp["refPx"])
        ct = float(sp["ctVal"])
        lot = float(sp["lotSz"])
        sz1 = max(math.floor(float(s["layer1_notional_usd"]) / (px * ct) / lot + 1e-9) * lot, float(sp["minSz"]))
        n1 = sz1 * ct * px
        n2 = n1 * float(s["layerScale"])
        sz2 = math.floor(n2 / (px * ct) / lot + 1e-9) * lot
        n2 = sz2 * ct * px if sz2 > 0 else n2
        trigger2 = px * (1 - float(s["layerStepPct"]) / 100) if s["side"] == "long" else px * (1 + float(s["layerStepPct"]) / 100)
        rows.append({
            "inst_id": s["inst_id"], "side": s["side"], "lev": s["leverage"], "layers": s["maxLayers"],
            "scale": s["layerScale"], "step": s["layerStepPct"], "tp": s["takeProfitPct"], "sl": s["stopLossPct"],
            "ref_px": px, "sz1": sz1, "notional1": round(n1, 4), "margin1": round(n1 / float(s["leverage"]), 4),
            "sz2": sz2, "notional2": round(n2, 4), "trigger2": round(trigger2, 8),
            "tp_px": round(px * (1 + float(s["takeProfitPct"]) / 100) if s["side"] == "long" else px * (1 - float(s["takeProfitPct"]) / 100), 8),
            "sl_px": round(px - float(s["stopLossPct"]) / 100 * px / float(s["leverage"]) if s["side"] == "long"
                           else px + float(s["stopLossPct"]) / 100 * px / float(s["leverage"]), 8),
            "capital": float(s["capital_usd"]),
        })
    tot1 = sum(r["notional1"] for r in rows)
    tot2 = sum(r["notional2"] for r in rows)
    tot_cap = sum(r["capital"] for r in rows)
    print(f"strategies={len(rows)} notional1={tot1:.3f}U notional2(if all layer2)={tot2:.3f}U capital={tot_cap:.3f}U")
    with open(os.path.join(OUT, "orders2.csv"), "w", encoding="utf-8-sig", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("wrote orders2.csv")


if __name__ == "__main__":
    main()
