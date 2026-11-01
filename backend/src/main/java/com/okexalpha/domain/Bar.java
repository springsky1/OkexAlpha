package com.okexalpha.domain;

/** K 线周期（OKX bar 参数全集，含 utc 变体）。 */
public enum Bar {
    M1("1m", 60_000L),
    M3("3m", 180_000L),
    M5("5m", 300_000L),
    M15("15m", 900_000L),
    M30("30m", 1_800_000L),
    H1("1H", 3_600_000L),
    H2("2H", 7_200_000L),
    H4("4H", 14_400_000L),
    H6("6H", 21_600_000L),
    H12("12H", 43_200_000L),
    D1("1D", 86_400_000L),
    D2("2D", 172_800_000L),
    D3("3D", 259_200_000L),
    W1("1W", 604_800_000L),
    MO1("1M", 2_592_000_000L),
    MO3("3M", 7_776_000_000L),
    H6_UTC("6Hutc", 21_600_000L),
    H12_UTC("12Hutc", 43_200_000L),
    D1_UTC("1Dutc", 86_400_000L),
    D2_UTC("2Dutc", 172_800_000L),
    D3_UTC("3Dutc", 259_200_000L),
    W1_UTC("1Wutc", 604_800_000L),
    MO1_UTC("1Mutc", 2_592_000_000L),
    MO3_UTC("3Mutc", 7_776_000_000L);

    private final String code;
    private final long intervalMs;

    Bar(String code, long intervalMs) {
        this.code = code;
        this.intervalMs = intervalMs;
    }

    public String code() {
        return code;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public static Bar fromCode(String code) {
        for (Bar bar : values()) {
            if (bar.code.equals(code)) {
                return bar;
            }
        }
        throw new IllegalArgumentException("invalid bar: " + code);
    }
}
