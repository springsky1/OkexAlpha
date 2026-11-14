# -*- coding: utf-8 -*-
"""
OKX v5 REST 最小客户端（USDT 本位永续 / 双向持仓）。

只实现策略需要的五个能力：
  candles  公开行情（无需签名）
  instrument 合约规格（ctVal / lotSz / minSz / tickSz / 最大杠杆）
  balance  账户余额
  positions 当前持仓
  place_order / set_leverage  下单与设杠杆（需要 API Key，且 REAL 模式要显式确认）

签名口径与官方一致：sign = base64(hmac_sha256(secret, ts + method + requestPath + body))，
其中 requestPath 含 querystring。所有金额单位为 USDT，张数为合约张数。
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

BASE = "https://www.okx.com"


@dataclass
class Credentials:
    api_key: str = ""
    secret: str = ""
    passphrase: str = ""
    simulated: bool = False  # True 时使用模拟盘头 x-simulated-trading: 1


class OkxError(RuntimeError):
    pass


class OkxClient:
    def __init__(self, creds: Credentials | None = None, base: str = BASE, timeout: float = 15.0):
        self.creds = creds or Credentials()
        self.base = base.rstrip("/")
        self.timeout = timeout

    # ---------- 底层 ----------
    def _request(self, method: str, path: str, params: dict | None = None,
                 body: dict | None = None, auth: bool = False) -> Any:
        params = params or {}
        qs = ("?" + urllib.parse.urlencode(params, doseq=True)) if params else ""
        payload = ""
        if body is not None:
            # separators 去掉空格，保证签名与发送的字节完全一致
            payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False)
        # 不带浏览器 UA 会被 OKX 前置的 WAF 直接 403（urllib 默认 UA 会中招）
        headers = {"Content-Type": "application/json",
                   "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
        if auth:
            ts = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
            prehash = f"{ts}{method.upper()}{path}{qs}{payload}"
            mac = hmac.new(self.creds.secret.encode(), prehash.encode(), hashlib.sha256).digest()
            headers.update({
                "OK-ACCESS-KEY": self.creds.api_key,
                "OK-ACCESS-SIGN": base64.b64encode(mac).decode(),
                "OK-ACCESS-TIMESTAMP": ts,
                "OK-ACCESS-PASSPHRASE": self.creds.passphrase,
            })
            if self.creds.simulated:
                headers["x-simulated-trading"] = "1"
        req = urllib.request.Request(self.base + path + qs,
                                     data=payload.encode() if payload else None,
                                     headers=headers, method=method.upper())
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            data = json.loads(resp.read().decode())
        if data.get("code") != "0":
            raise OkxError(f"okx error {data.get('code')}: {data.get('msg')}")
        return data.get("data")

    # ---------- 公开 ----------
    def candles(self, inst_id: str, bar: str = "1H", limit: int = 200) -> list[dict]:
        """返回按时间升序的 K 线：ts / o / h / l / c。"""
        rows = self._request("GET", "/api/v5/market/candles",
                             {"instId": inst_id, "bar": bar, "limit": min(limit, 300)})
        out = [{"ts": int(r[0]), "o": float(r[1]), "h": float(r[2]),
                "l": float(r[3]), "c": float(r[4])} for r in rows]
        return sorted(out, key=lambda x: x["ts"])

    def instrument(self, inst_id: str) -> dict:
        d = self._request("GET", "/api/v5/public/instruments",
                          {"instId": inst_id, "instType": "SWAP"})[0]
        return {
            "instId": d["instId"],
            "ctVal": float(d["ctVal"]),
            "ctValCcy": d["ctValCcy"],
            "lotSz": float(d["lotSz"]),
            "minSz": float(d["minSz"]),
            "tickSz": float(d["tickSz"]),
            "maxLever": float(d.get("lever") or 1),
            "state": d.get("state"),
        }

    # ---------- 私有 ----------
    def balance_usdt(self) -> dict:
        d = self._request("GET", "/api/v5/account/balance", {"ccy": "USDT"}, auth=True)[0]
        row = (d.get("details") or [{}])[0]
        return {"totalEq": float(d.get("totalEq") or 0),
                "avail": float(row.get("availBal") or 0),
                "cash": float(row.get("cashBal") or 0)}

    def positions(self, inst_id: str | None = None) -> list[dict]:
        params = {"instType": "SWAP"}
        if inst_id:
            params["instId"] = inst_id
        return self._request("GET", "/api/v5/account/positions", params, auth=True) or []

    def set_leverage(self, inst_id: str, lever: int, pos_side: str,
                     mgn_mode: str = "isolated") -> Any:
        body = {"instId": inst_id, "lever": str(lever), "mgnMode": mgn_mode}
        if pos_side:
            body["posSide"] = pos_side  # 双向持仓必须带，否则 51000
        return self._request("POST", "/api/v5/account/set-leverage", body=body, auth=True)

    def place_order(self, inst_id: str, side: str, pos_side: str, sz: str,
                    ord_type: str = "market", px: str | None = None,
                    cl_ord_id: str | None = None, reduce_only: bool = False) -> dict:
        body = {
            "instId": inst_id,
            "tdMode": "isolated",
            "side": side,              # buy / sell
            "posSide": pos_side,       # long / short（双向持仓必填）
            "ordType": ord_type,       # market / limit
            "sz": sz,                  # 合约张数（字符串，按 lotSz 取整）
        }
        if px and ord_type == "limit":
            body["px"] = px
        if cl_ord_id:
            body["clOrdId"] = cl_ord_id[:32]
        if reduce_only:
            body["reduceOnly"] = True
        data = self._request("POST", "/api/v5/trade/order", body=body, auth=True)
        return data[0] if data else {}
