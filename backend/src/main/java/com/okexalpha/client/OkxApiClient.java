package com.okexalpha.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.client.OkxApiException.Kind;
import com.okexalpha.client.model.OkxFundingRate;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.KlineType;
import com.okexalpha.domain.Period;
import com.okexalpha.domain.RatioType;

/**
 * OKX 公有 REST 客户端：限流 + 重试 + 错误映射 + 调用日志。全部返回 data 节点，不做业务解释。
 */
@Component
public class OkxApiClient {

    private static final Logger log = LoggerFactory.getLogger(OkxApiClient.class);

    public static final String PATH_INSTRUMENTS = "/api/v5/public/instruments";
    public static final String PATH_FUNDING_RATE = "/api/v5/public/funding-rate";
    public static final String PATH_FUNDING_RATE_HISTORY = "/api/v5/public/funding-rate-history";
    public static final String PATH_OPEN_INTEREST = "/api/v5/public/open-interest";
    public static final String PATH_OI_VOLUME = "/api/v5/rubik/stat/contracts/open-interest-volume";
    public static final String PATH_TAKER_VOLUME = "/api/v5/rubik/stat/taker-volume";
    public static final String PATH_TAKER_VOLUME_CONTRACT = "/api/v5/rubik/stat/taker-volume-contract";

    private final OkxProperties properties;
    private final HttpTransport transport;
    private final OkxRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final ApiCallLogSink apiCallLogSink;

    public OkxApiClient(OkxProperties properties, HttpTransport transport, OkxRateLimiter rateLimiter,
            ObjectMapper objectMapper, ApiCallLogSink apiCallLogSink) {
        this.properties = properties;
        this.transport = transport;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.apiCallLogSink = apiCallLogSink;
    }

    // ---------------------------------------------------------------- instruments

    public List<JsonNode> getInstruments(String instType, String instFamily) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instType", instType);
        if (instFamily != null && !instFamily.isBlank()) {
            query.put("instFamily", instFamily);
        }
        return elements(fetchData(PATH_INSTRUMENTS, null, query));
    }

    // ---------------------------------------------------------------- klines

    public List<JsonNode> getCandles(KlineType klineType, String instId, String bar, Long after, Long before, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instId", instId);
        query.put("bar", bar);
        if (after != null) {
            query.put("after", Long.toString(after));
        }
        if (before != null) {
            query.put("before", Long.toString(before));
        }
        query.put("limit", Integer.toString(limit));
        return elements(fetchData(klineType.path(), instId, query));
    }

    // ---------------------------------------------------------------- funding

    public List<OkxFundingRate> getFundingRate(String instId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instId", instId);
        JsonNode data = fetchData(PATH_FUNDING_RATE, instId, query);
        return fundingRates(data);
    }

    public List<OkxFundingRate> getFundingRateHistory(String instId, Long after, Long before, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instId", instId);
        if (after != null) {
            query.put("after", Long.toString(after));
        }
        if (before != null) {
            query.put("before", Long.toString(before));
        }
        query.put("limit", Integer.toString(limit));
        JsonNode data = fetchData(PATH_FUNDING_RATE_HISTORY, instId, query);
        return fundingRates(data);
    }

    // ---------------------------------------------------------------- rubik

    public List<JsonNode> getLongShortRatio(RatioType ratioType, String scope, Period period, long beginMs, long endMs) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(ratioType.scopeKind() == RatioType.ScopeKind.CCY ? "ccy" : "instId", scope);
        query.put("period", period.code());
        query.put("begin", Long.toString(beginMs));
        query.put("end", Long.toString(endMs));
        return elements(fetchData(ratioType.path(), scope, query));
    }

    public List<JsonNode> getOpenInterestVolume(String ccy, Period period, long beginMs, long endMs) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ccy", ccy);
        query.put("period", period.code());
        query.put("begin", Long.toString(beginMs));
        query.put("end", Long.toString(endMs));
        return elements(fetchData(PATH_OI_VOLUME, ccy, query));
    }

    public List<JsonNode> getTakerVolumeByCcy(String ccy, String instType, Period period, long beginMs, long endMs) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ccy", ccy);
        query.put("instType", instType);
        query.put("period", period.code());
        query.put("begin", Long.toString(beginMs));
        query.put("end", Long.toString(endMs));
        return elements(fetchData(PATH_TAKER_VOLUME, ccy, query));
    }

    public List<JsonNode> getTakerVolumeByInst(String instId, Period period, long beginMs, long endMs) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("instId", instId);
        query.put("period", period.code());
        query.put("begin", Long.toString(beginMs));
        query.put("end", Long.toString(endMs));
        return elements(fetchData(PATH_TAKER_VOLUME_CONTRACT, instId, query));
    }

    public List<JsonNode> getOpenInterest(String instType, String instId) {
        Map<String, String> query = new LinkedHashMap<>();
        if (instType != null && !instType.isBlank()) {
            query.put("instType", instType);
        }
        if (instId != null && !instId.isBlank()) {
            query.put("instId", instId);
        }
        return elements(fetchData(PATH_OPEN_INTEREST, instId, query));
    }

    // ---------------------------------------------------------------- core

    JsonNode fetchData(String path, String instId, Map<String, String> query) {
        String group = groupOf(path);
        int maxAttempts = Math.max(1, properties.retry().maxAttempts());
        OkxApiException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (properties.rateLimit().enabled()) {
                rateLimiter.acquire(group);
            }
            URI uri = buildUri(path, query);
            long startNanos = System.nanoTime();
            TransportResponse response;
            try {
                response = transport.get(uri, headers());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new OkxApiException(Kind.NETWORK, null, 0, "interrupted while calling " + path,
                        TransportResponse.failed(ex.toString()));
            } catch (IOException | RuntimeException ex) {
                long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
                record(path, instId, null, null, ex.toString(), null, elapsed);
                OkxApiException failure = new OkxApiException(Kind.NETWORK, null, 0,
                        "transport error on " + path + ": " + ex.getMessage(), TransportResponse.failed(ex.toString()));
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    throw failure;
                }
                backoff(attempt, path);
                continue;
            }

            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            JsonNode root = null;
            try {
                root = objectMapper.readTree(response.body());
            } catch (Exception ex) {
                log.debug("non-json response from {}: {}", path, abbreviate(response.body()));
            }
            String code = root == null ? null : root.path("code").asText(null);
            String msg = root == null ? null : root.path("msg").asText("");
            Integer rows = root != null && root.path("data").isArray() ? root.path("data").size() : null;
            record(path, instId, response.statusCode(), code, msg, rows, elapsed);

            if (response.statusCode() == 429) {
                OkxApiException failure = new OkxApiException(Kind.RATE_LIMITED, code, 429,
                        "http 429 too many requests on " + path, response);
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    throw failure;
                }
                backoff(attempt, path);
                continue;
            }
            if (response.statusCode() >= 500) {
                OkxApiException failure = new OkxApiException(Kind.UPSTREAM_ERROR, code, response.statusCode(),
                        "http " + response.statusCode() + " on " + path + ": " + abbreviate(response.body()), response);
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    throw failure;
                }
                backoff(attempt, path);
                continue;
            }
            if (root == null) {
                OkxApiException failure = new OkxApiException(Kind.UPSTREAM_ERROR, null, response.statusCode(),
                        "unparsable response on " + path + ": " + abbreviate(response.body()), response);
                lastFailure = failure;
                if (attempt == maxAttempts) {
                    throw failure;
                }
                backoff(attempt, path);
                continue;
            }
            if (!"0".equals(code)) {
                OkxApiException failure = new OkxApiException(kindOf(code), code, response.statusCode(),
                        "okx error " + code + ": " + msg, response);
                if (!failure.retryable() || attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
                backoff(attempt, path);
                continue;
            }
            return root.path("data");
        }
        throw lastFailure != null ? lastFailure
                : new OkxApiException(Kind.UPSTREAM_ERROR, null, 0, "exhausted retries on " + path, null);
    }

    private static Kind kindOf(String code) {
        if (code == null) {
            return Kind.UPSTREAM_ERROR;
        }
        return switch (code) {
            case "50011", "50013", "50061" -> Kind.RATE_LIMITED;
            case "50030" -> Kind.RANGE_EXHAUSTED;
            case "51000", "51001", "51002", "51010", "51014" -> Kind.INVALID_PARAM;
            default -> Kind.UPSTREAM_ERROR;
        };
    }

    private static String groupOf(String path) {
        if (path.startsWith("/api/v5/rubik/")) {
            return OkxClientConfig.GROUP_RUBIK;
        }
        if (path.contains("/market/history")) {
            return OkxClientConfig.GROUP_CANDLES;
        }
        if (path.contains("/funding-rate")) {
            return OkxClientConfig.GROUP_FUNDING;
        }
        return OkxClientConfig.GROUP_INSTRUMENTS;
    }

    private void backoff(int attempt, String path) {
        long base = Math.max(50L, properties.retry().baseBackoffMs());
        long delay = base * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 250);
        long total = Math.min(delay + jitter, 10_000L);
        log.debug("retrying {} after {} ms (attempt {})", path, total, attempt);
        try {
            Thread.sleep(total);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OkxApiException(Kind.NETWORK, null, 0, "interrupted during backoff", null);
        }
    }

    private URI buildUri(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(properties.baseUrl());
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    sb.append('&');
                }
                first = false;
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(sb.toString());
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", properties.userAgent());
        headers.put("Accept", "application/json");
        return headers;
    }

    private void record(String path, String instId, Integer httpStatus, String okxCode, String okxMsg,
            Integer rowCount, long elapsed) {
        if (apiCallLogSink == null) {
            return;
        }
        try {
            apiCallLogSink.log(new ApiCallRecord(path, instId, httpStatus, okxCode,
                    okxMsg == null ? null : abbreviate(okxMsg), rowCount, elapsed));
        } catch (RuntimeException ex) {
            log.debug("cannot record api call: {}", ex.toString());
        }
    }

    private static List<JsonNode> elements(JsonNode data) {
        List<JsonNode> list = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(list::add);
        }
        return list;
    }

    private List<OkxFundingRate> fundingRates(JsonNode data) {
        List<OkxFundingRate> list = new ArrayList<>();
        if (data == null || !data.isArray()) {
            return list;
        }
        for (JsonNode node : data) {
            list.add(objectMapper.convertValue(node, OkxFundingRate.class));
        }
        return list;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }
}
