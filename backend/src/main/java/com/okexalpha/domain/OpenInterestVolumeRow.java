package com.okexalpha.domain;

import java.math.BigDecimal;

/** okx_open_interest_volume 一行（rubik：[ts, oi, vol]）。 */
public record OpenInterestVolumeRow(String ccy, String period, long tsMs, BigDecimal oi, BigDecimal vol) {
}
