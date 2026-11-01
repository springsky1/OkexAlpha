package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_taker_volume 一行（rubik：[ts, sellVol, buyVol]）。 */
public record TakerVolumeRow(
        String scopeKind,
        String scope,
        String marketType,
        String period,
        long tsMs,
        BigDecimal sellVol,
        BigDecimal buyVol) {
}
