package com.okexalpha.security;

/**
 * 凭证脱敏工具：所有对外响应（HTTP JSON、日志、文档）都不得出现完整的 apiKey / secretKey / passphrase。
 *
 * <p>规则刻意保守：apiKey 只保留首 4 + 末 4，短于 12 位一律 {@code ****}；uid 这类账号标识保留首 2 + 末 2。
 * 该类只做展示脱敏，不负责存储——凭证的真实来源只有环境变量（见 docs/SECURITY.md）。</p>
 */
public final class SecretMasker {

    private static final String STAR = "****";

    private SecretMasker() {
    }

    /** 是否配置了非空值。 */
    public static boolean configured(String value) {
        return value != null && !value.isBlank();
    }

    /** apiKey 之类的高敏字符串：首 4 + **** + 末 4。 */
    public static String mask(String secret) {
        if (!configured(secret)) {
            return null;
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= 12) {
            return STAR;
        }
        return trimmed.substring(0, 4) + STAR + trimmed.substring(trimmed.length() - 4);
    }

    /** 账号 uid 这类中敏标识：首 2 + *** + 末 2。 */
    public static String maskMiddle(String value) {
        if (!configured(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return "***";
        }
        return trimmed.substring(0, 2) + "***" + trimmed.substring(trimmed.length() - 2);
    }
}
