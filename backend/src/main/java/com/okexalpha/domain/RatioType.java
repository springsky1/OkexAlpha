package com.okexalpha.domain;

/** 多空比类型（含精英持仓）。 */
public enum RatioType {
    /** 全平台多空账户数比值，按币。 */
    LSR_ACCOUNT_CCY("/api/v5/rubik/stat/contracts/long-short-account-ratio", ScopeKind.CCY),
    /** 单合约多空账户数比值。 */
    LSR_ACCOUNT_CONTRACT("/api/v5/rubik/stat/contracts/long-short-account-ratio-contract", ScopeKind.INST),
    /** 大户（top trader）账户数多空比。 */
    TOP_TRADER_ACCOUNT("/api/v5/rubik/stat/contracts/long-short-account-ratio-contract-top-trader", ScopeKind.INST),
    /** 精英持仓量多空比（top trader 持仓）。 */
    TOP_TRADER_POSITION("/api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader", ScopeKind.INST);

    public enum ScopeKind {
        CCY, INST
    }

    private final String path;
    private final ScopeKind scopeKind;

    RatioType(String path, ScopeKind scopeKind) {
        this.path = path;
        this.scopeKind = scopeKind;
    }

    public String path() {
        return path;
    }

    public ScopeKind scopeKind() {
        return scopeKind;
    }

    public static RatioType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("ratioType is required");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "invalid ratioType: " + code + " (allowed: LSR_ACCOUNT_CCY,LSR_ACCOUNT_CONTRACT,TOP_TRADER_ACCOUNT,TOP_TRADER_POSITION)");
        }
    }
}
