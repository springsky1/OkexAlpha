package com.okexalpha.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OKX v5 私有接口签名：base64(HMAC-SHA256(timestamp + method + requestPath + body, secretKey))。
 *
 * <p>要点（详见 docs/OKX-TRADE-PARAMS.md）：</p>
 * <ul>
 *   <li>timestamp 是 ISO-8601 UTC 毫秒（GET/POST/时间戳格式非法都会报 50102）；</li>
 *   <li>GET 的请求参数计入 requestPath（"?a=1&b=2" 要拼上），POST 的 body 用原始 JSON 字符串；</li>
 *   <li>签名只覆盖 requestPath，不含域名。</li>
 * </ul>
 */
public final class OkxSignature {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private OkxSignature() {
    }

    /** 当前时刻的 OKX 时间戳字符串（UTC 毫秒）。 */
    public static String timestamp(Instant now) {
        return TIMESTAMP_FORMAT.format(now);
    }

    /** 计算签名。 */
    public static String sign(String secretKey, String timestamp, String method, String requestPath, String body) {
        String payload = timestamp + method.toUpperCase() + requestPath + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign okx request", ex);
        }
    }
}
