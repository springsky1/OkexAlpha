package com.okexalpha.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OkxSignatureTest {

    /** 独立用 Python hmac 算出的向量，防止"实现自己验证自己"。 */
    private static final String VECTOR_SECRET = "secret-key-demo";
    private static final String VECTOR_TS = "2020-12-08T09:08:57.715Z";
    private static final String VECTOR_PATH = "/api/v5/account/balance?ccy=BTC";
    private static final String VECTOR_SIGN = "hmfe+FQq9dglrnta0V2TQ188fx3NFloPhrVQEDmat5k=";

    @Test
    @DisplayName("已知向量：GET 查询串计入 requestPath")
    void matchesKnownVector() {
        String actual = OkxSignature.sign(VECTOR_SECRET, VECTOR_TS, "GET", VECTOR_PATH, "");
        assertThat(actual).isEqualTo(VECTOR_SIGN);
    }

    @Test
    @DisplayName("大小写方法等价（内部统一大写）")
    void methodIsCaseInsensitive() {
        assertThat(OkxSignature.sign(VECTOR_SECRET, VECTOR_TS, "get", VECTOR_PATH, ""))
                .isEqualTo(VECTOR_SIGN);
    }

    @Test
    @DisplayName("body 参与签名：POST 加 body 后结果变化")
    void bodyIsPartOfPayload() {
        String withoutBody = OkxSignature.sign("k", VECTOR_TS, "POST", "/api/v5/trade/order", "");
        String withBody = OkxSignature.sign("k", VECTOR_TS, "POST", "/api/v5/trade/order", "{\"instId\":\"BTC-USDT-SWAP\"}");
        assertThat(withBody).isNotEqualTo(withoutBody);
    }

    @Test
    @DisplayName("null body 等价于空串")
    void nullBodyEqualsEmptyBody() {
        assertThat(OkxSignature.sign("k", VECTOR_TS, "POST", "/api/v5/trade/order", null))
                .isEqualTo(OkxSignature.sign("k", VECTOR_TS, "POST", "/api/v5/trade/order", ""));
    }

    @Test
    @DisplayName("签名是 32 字节 HMAC-SHA256 的 base64")
    void producesBase64Of32Bytes() {
        String sign = OkxSignature.sign("secret", VECTOR_TS, "GET", "/x", "");
        assertThat(Base64.getDecoder().decode(sign)).hasSize(32);
    }

    @Test
    @DisplayName("时间戳格式为 UTC 毫秒 ISO-8601")
    void timestampFormat() {
        assertThat(OkxSignature.timestamp(Instant.parse("2020-12-08T09:08:57.715Z")))
                .isEqualTo("2020-12-08T09:08:57.715Z");
    }
}
