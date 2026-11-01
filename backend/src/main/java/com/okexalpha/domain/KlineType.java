package com.okexalpha.domain;

/** K 线两类：蜡烛 / 标记价格（只针对永续合约 -SWAP；不支持指数 K 线）。 */
public enum KlineType {
    CANDLE("/api/v5/market/history-candles", true),
    MARK("/api/v5/market/history-mark-price-candles", false);

    private final String path;
    private final boolean hasVolume;

    KlineType(String path, boolean hasVolume) {
        this.path = path;
        this.hasVolume = hasVolume;
    }

    public String path() {
        return path;
    }

    /** CANDLE 返回 9 列（含量），MARK 返回 6 列。 */
    public boolean hasVolume() {
        return hasVolume;
    }

    public static KlineType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("klineType is required");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid klineType: " + code + " (allowed: CANDLE,MARK)");
        }
    }
}
