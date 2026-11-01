package com.okexalpha.domain;

/**
 * rubik 统计周期。period 只支持 5m/1H/1D，且各自有保留期与单次可取的最大时间窗。
 */
public enum Period {
    M5("5m", 48L * 3_600_000L, 8L * 3_600_000L),
    H1("1H", 30L * 86_400_000L, 4L * 86_400_000L),
    D1("1D", 180L * 86_400_000L, 90L * 86_400_000L);

    private final String code;
    private final long retentionMs;
    private final long windowMs;

    Period(String code, long retentionMs, long windowMs) {
        this.code = code;
        this.retentionMs = retentionMs;
        this.windowMs = windowMs;
    }

    public String code() {
        return code;
    }

    /** 上游保留期：更早的数据已不可取。 */
    public long retentionMs() {
        return retentionMs;
    }

    /** 单次请求的最大时间窗（保证点数 <= 100~576）。 */
    public long windowMs() {
        return windowMs;
    }

    public static Period fromCode(String code) {
        for (Period period : values()) {
            if (period.code.equals(code)) {
                return period;
            }
        }
        throw new IllegalArgumentException("invalid period: " + code + " (allowed: 5m,1H,1D)");
    }
}
