package com.okexalpha.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.security.OkxCredentials;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OKX v5 私有（签名）REST 客户端：只做传输、签名、错误映射与调用日志，不做业务判断。
 *
 * <p>默认 mode=SHADOW 时不会被调用；DEMO 自动带 {@code x-simulated-trading: 1}。
 * 限流组固定 trade（10 次/2s），与公有接口的 instruments/candles/funding/rubik 组隔离。</p>
 *
 * <p>安全约束：凭证一律从 {@link OkxCredentials} 取（环境变量或数据库密文解密后的内存快照），
 * 本类不读明文配置、不落密文、不写日志；也不做重试（下单类请求重试会重复成交）——
 * 失败直接把 {@link OkxApiException} 抛给调用方。</p>
 */
@Component
public class OkxTradeClient {

    private static final Logger log = LoggerFactory.getLogger(OkxTradeClient.class);

    public static final String PATH_ACCOUNT_CONFIG = "/api/v5/account/config";
    public static final String PATH_BALANCE = "/api/v5/account/balance";
    public static final String PATH_POSITIONS = "/api/v5/account/positions";
    public static final String PATH_PENDING_ORDERS = "/api/v5/trade/orders-pending";
    public static final String PATH_ORDERS_HISTORY = "/api/v5/trade/orders-history";
    public static final String PATH_ORDERS_HISTORY_ARCHIVE = "/api/v5/trade/orders-history-archive";
    public static final String PATH_ORDER = "/api/v5/trade/order";
    public static final String PATH_CLOSE_POSITION = "/api/v5/trade/close-position";
    public static final String PATH_SET_LEVERAGE = "/api/v5/account/set-leverage";
    public static final String PATH_SET_POSITION_MODE = "/api/v5/account/set-position-mode";
    public static final String PATH_MAX_SIZE = "/api/v5/account/max-size";
    public static final String PATH_POSITIONS_HISTORY = "/api/v5/account/positions-history";
    public static final String PATH_FILLS_HISTORY = "/api/v5/trade/fills-history";
    public static final String PATH_BILLS = "/api/v5/account/bills";
    public static final String PATH_BORROW_REPAY_HISTORY = "/api/v5/account/borrow-repay-history";

    private final OkxProperties properties;
    /** 凭证来源：环境变量 > 数据库密文 > 未配置（见 OkxCredentials）。 */
    private final OkxCredentials credentials;
    private final HttpTransport transport;
    private final OkxRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final ApiCallLogSink apiCallLogSink;

    public OkxTradeClient(OkxProperties properties, OkxCredentials credentials, HttpTransport transport,
            OkxRateLimiter rateLimiter, ObjectMapper objectMapper, ApiCallLogSink apiCallLogSink) {
        this.properties = properties;
        this.credentials = credentials;
        this.transport = transport;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.apiCallLogSink = apiCallLogSink;
    }

    // ------------------------------------------------------------------ 查询

    /** 账户配置（uid/acctLv/posMode/perm 等）；账户节点用来展示账户身份与模式。 */
    public List<JsonNode> accountConfig() {
        return call("GET", PATH_ACCOUNT_CONFIG, null, null);
    }

    public List<JsonNode> balance() {
        return call("GET", PATH_BALANCE, null, null);
    }

    public List<JsonNode> positions(String instType) {
        Map<String, String> query = new LinkedHashMap<>();
        if (instType != null && !instType.isBlank()) {
            query.put("instType", instType);
        }
        return call("GET", PATH_POSITIONS, query.isEmpty() ? null : encode(query), null);
    }

    public List<JsonNode> pendingOrders(String instType) {
        Map<String, String> query = new LinkedHashMap<>();
        if (instType != null && !instType.isBlank()) {
            query.put("instType", instType);
        }
        return call("GET", PATH_PENDING_ORDERS, query.isEmpty() ? null : encode(query), null);
    }

    /**
     * 历史订单（最近 7 天）。SWAP/FUTURES/OPTION 必须带 instType。
     *
     * @param limit 1..100（OKX 上限 100）
     */
    public List<JsonNode> ordersHistory(String instType, String instId, Integer limit) {
        return call("GET", PATH_ORDERS_HISTORY, ordersQuery(instType, instId, limit), null);
    }

    /** 历史订单归档（7 天~3 个月，需 begin/end 或不填按最近）。 */
    public List<JsonNode> ordersHistoryArchive(String instType, String instId, Integer limit) {
        return call("GET", PATH_ORDERS_HISTORY_ARCHIVE, ordersQuery(instType, instId, limit), null);
    }

    private static String instTypeOrSwap(String instType) {
        return instType == null || instType.isBlank()
                ? "SWAP"
                : instType.trim().toUpperCase(Locale.ROOT);
    }

    /** OKX 的 limit 上限 100，越界一律夹到 1..100。 */
    private static String limitText(Integer limit) {
        int size = limit == null ? 100 : limit;
        return String.valueOf(Math.min(Math.max(size, 1), 100));
    }

    private static String ordersQuery(String instType, String instId, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instType", instType == null || instType.isBlank() ? "SWAP" : instType.trim().toUpperCase(Locale.ROOT));
        if (instId != null && !instId.isBlank()) {
            query.put("instId", instId.trim());
        }
        int size = limit == null ? 100 : limit;
        query.put("limit", String.valueOf(Math.min(Math.max(size, 1), 100)));
        return encode(query);
    }

    /** 历史仓位（已平仓，最近 3 个月）；instType 必填，缺省按 SWAP。 */
    public List<JsonNode> positionsHistory(String instType, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instType", instTypeOrSwap(instType));
        query.put("limit", limitText(limit));
        return call("GET", PATH_POSITIONS_HISTORY, encode(query), null);
    }

    /** 成交明细（最近 3 个月）；instType 必填，缺省按 SWAP。 */
    public List<JsonNode> fills(String instType, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instType", instTypeOrSwap(instType));
        query.put("limit", limitText(limit));
        return call("GET", PATH_FILLS_HISTORY, encode(query), null);
    }

    /** 资金账单（最近 7 天）。 */
    public List<JsonNode> bills(Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", limitText(limit));
        return call("GET", PATH_BILLS, encode(query), null);
    }

    /** 借币/还币记录；instId 可选，只查单个币种。 */
    public List<JsonNode> borrowRepay(Integer limit, String instId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", limitText(limit));
        if (instId != null && !instId.isBlank()) {
            query.put("instId", instId.trim());
        }
        return call("GET", PATH_BORROW_REPAY_HISTORY, encode(query), null);
    }

    public List<JsonNode> maxSize(String instId, String tdMode) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instId", instId);
        query.put("tdMode", tdMode == null ? "isolated" : tdMode);
        return call("GET", PATH_MAX_SIZE, encode(query), null);
    }

    // ------------------------------------------------------------------ 变更

    public List<JsonNode> setLeverage(String instId, String lever, String mgnMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", instId);
        body.put("lever", lever);
        body.put("mgnMode", mgnMode == null ? "isolated" : mgnMode);
        return call("POST", PATH_SET_LEVERAGE, null, json(body));
    }

    public List<JsonNode> setPositionMode(String posMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("posMode", posMode);
        return call("POST", PATH_SET_POSITION_MODE, null, json(body));
    }

    public List<JsonNode> placeOrder(Map<String, Object> body) {
        return call("POST", PATH_ORDER, null, json(body));
    }

    public List<JsonNode> closePosition(Map<String, Object> body) {
        return call("POST", PATH_CLOSE_POSITION, null, json(body));
    }

    // ------------------------------------------------------------------ 内部

    private String json(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize okx request body", ex);
        }
    }

    private static String encode(Map<String, String> query) {
        StringBuilder sb = new StringBuilder();
        query.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private List<JsonNode> call(String method, String path, String query, String body) {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        OkxCredentials.Credential credential = credentials.current();
        String apiKey = blankToNull(credential.apiKey());
        String secretKey = blankToNull(credential.secretKey());
        String passphrase = blankToNull(credential.passphrase());
        if (apiKey == null || secretKey == null || passphrase == null) {
            throw new OkxApiException(OkxApiException.Kind.INVALID_PARAM, "MISSING_CREDENTIALS", 0,
                    "okx trade api credentials are not configured: set env OKX_API_KEY/OKX_SECRET_KEY/OKX_PASSPHRASE"
                            + " or POST /api/v1/account/credentials",
                    null);
        }
        String requestPath = query == null || query.isBlank() ? path : path + "?" + query;
        String timestamp = OkxSignature.timestamp(Instant.now());
        String signature = OkxSignature.sign(secretKey, timestamp, method, requestPath, body);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("OK-ACCESS-KEY", apiKey);
        headers.put("OK-ACCESS-SIGN", signature);
        headers.put("OK-ACCESS-TIMESTAMP", timestamp);
        headers.put("OK-ACCESS-PASSPHRASE", passphrase);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", properties.userAgent());
        String mode = trading.mode() == null ? "SHADOW" : trading.mode().trim().toUpperCase();
        if ("DEMO".equals(mode)) {
            headers.put("x-simulated-trading", "1");
        }

        URI uri = URI.create(properties.baseUrl() + requestPath);
        rateLimiter.acquire(OkxClientConfig.GROUP_TRADE, OkxClientConfig.GROUP_GLOBAL);
        long startedAt = System.nanoTime();
        TransportResponse response;
        try {
            response = "GET".equals(method)
                    ? transport.get(uri, headers)
                    : "PUT".equals(method) ? transport.put(uri, headers, body) : transport.post(uri, headers, body);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            apiCallLogSink.log(new ApiCallRecord(path, null, 0, "NETWORK", ex.getMessage(), null,
                    (System.nanoTime() - startedAt) / 1_000_000L));
            throw new OkxApiException(OkxApiException.Kind.NETWORK, null, 0,
                    "okx trade request failed: " + ex.getMessage(), null);
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        JsonNode root = parse(response.body());
        String code = root == null ? null : text(root, "code");
        String msg = root == null ? null : text(root, "msg");
        List<JsonNode> data = root == null ? List.of() : elements(root.get("data"));
        apiCallLogSink.log(new ApiCallRecord(path, null, response.statusCode(), code, msg, data.size(), elapsedMs));
        if (response.statusCode() != 200 || code == null || !"0".equals(code)) {
            throw new OkxApiException(kindOf(response.statusCode(), code), code, response.statusCode(),
                    "okx trade error " + code + ": " + (msg == null ? response.body() : msg), response);
        }
        log.debug("okx trade {} {} -> {} row(s) in {}ms", method, requestPath, data.size(), elapsedMs);
        return data;
    }

    private static OkxApiException.Kind kindOf(int httpStatus, String code) {
        if (httpStatus == 429) {
            return OkxApiException.Kind.RATE_LIMITED;
        }
        if (code != null) {
            return switch (code) {
                case "50011", "50013", "50061", "50102" -> OkxApiException.Kind.RATE_LIMITED;
                case "50030" -> OkxApiException.Kind.RANGE_EXHAUSTED;
                case "51000", "51001", "51002", "51004", "51006", "51008", "51010", "51014", "51020", "51121",
                        "51169", "MISSING_CREDENTIALS" -> OkxApiException.Kind.INVALID_PARAM;
                default -> OkxApiException.Kind.UPSTREAM_ERROR;
            };
        }
        return httpStatus >= 500 ? OkxApiException.Kind.UPSTREAM_ERROR : OkxApiException.Kind.NETWORK;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<JsonNode> elements(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>(array.size());
        array.forEach(out::add);
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
