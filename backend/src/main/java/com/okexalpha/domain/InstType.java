package com.okexalpha.domain;

/** OKX 品种类型（instruments?instType=）。 */
public enum InstType {
    SPOT, MARGIN, SWAP, FUTURES, OPTION;

    public String code() {
        return name();
    }

    public boolean isDerivative() {
        return this == SWAP || this == FUTURES || this == OPTION;
    }

    public static InstType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("instType is required");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid instType: " + code + " (allowed: SPOT,MARGIN,SWAP,FUTURES,OPTION)");
        }
    }
}
