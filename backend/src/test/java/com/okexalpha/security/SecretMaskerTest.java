package com.okexalpha.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对外响应里的凭证脱敏：只留首尾，任何完整密钥都不得泄漏。 */
class SecretMaskerTest {

    private static final String API_KEY = "AAAA1111BBBB2222CCCC3333";

    @Test
    @DisplayName("apiKey 保留首 4 + 末 4，中间不可见")
    void masksApiKeyKeepingFourOnEachSide() {
        assertThat(SecretMasker.mask(API_KEY)).isEqualTo("AAAA****3333");
        assertThat(SecretMasker.mask(API_KEY)).doesNotContain("1111BBBB").doesNotContain("2222CCCC");
    }

    @Test
    @DisplayName("13 位密钥同样首 4 + 末 4")
    void masksThirteenCharacterSecret() {
        assertThat(SecretMasker.mask("1234567890abc")).isEqualTo("1234****0abc");
    }

    @Test
    @DisplayName("12 位及以下一律 ****，避免首尾拼出原文")
    void shortSecretsBecomeFourStars() {
        assertThat(SecretMasker.mask("123456789012")).isEqualTo("****");
        assertThat(SecretMasker.mask("SHORTSECRET")).isEqualTo("****");
    }

    @Test
    @DisplayName("空白与 null 返回 null（表示未配置，而不是空串）")
    void blankSecretsBecomeNull() {
        assertThat(SecretMasker.mask(null)).isNull();
        assertThat(SecretMasker.mask("")).isNull();
        assertThat(SecretMasker.mask("    ")).isNull();
        assertThat(SecretMasker.mask("\t\n")).isNull();
    }

    @Test
    @DisplayName("脱敏前会 trim")
    void maskTrimsSurroundingWhitespace() {
        assertThat(SecretMasker.mask("  " + API_KEY + "  ")).isEqualTo("AAAA****3333");
    }

    @Test
    @DisplayName("uid 保留首 2 + 末 2")
    void masksMiddleOfUid() {
        assertThat(SecretMasker.maskMiddle("1234567890")).isEqualTo("12***90");
    }

    @Test
    @DisplayName("uid 长度 <= 6 一律 ***")
    void shortUidBecomesThreeStars() {
        assertThat(SecretMasker.maskMiddle("123456")).isEqualTo("***");
        assertThat(SecretMasker.maskMiddle("12345")).isEqualTo("***");
    }

    @Test
    @DisplayName("uid 长度 7 时首 2 + 末 2")
    void sevenCharacterUid() {
        assertThat(SecretMasker.maskMiddle("1234567")).isEqualTo("12***67");
    }

    @Test
    @DisplayName("uid 空白与 null 返回 null")
    void blankUidBecomesNull() {
        assertThat(SecretMasker.maskMiddle(null)).isNull();
        assertThat(SecretMasker.maskMiddle(" ")).isNull();
    }

    @Test
    @DisplayName("configured 只看非 null 且非空白")
    void configuredChecksNullOrBlank() {
        assertThat(SecretMasker.configured("abc")).isTrue();
        assertThat(SecretMasker.configured("  x  ")).isTrue();
        assertThat(SecretMasker.configured("")).isFalse();
        assertThat(SecretMasker.configured("   ")).isFalse();
        assertThat(SecretMasker.configured(null)).isFalse();
    }

    @Test
    @DisplayName("脱敏结果永不等同于输入，也不含完整密钥")
    void maskedValueNeverEqualsSecret() {
        assertThat(SecretMasker.mask(API_KEY)).isNotEqualTo(API_KEY);
        assertThat(SecretMasker.maskMiddle("UID-1234567890")).isNotEqualTo("UID-1234567890").doesNotContain("D-123456");
    }
}
