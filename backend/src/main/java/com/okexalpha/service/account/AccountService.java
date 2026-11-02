package com.okexalpha.service.account;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.security.SecretMasker;

import tools.jackson.databind.JsonNode;

/**
 * 账户节点（只读）：交易所账户配置 / 余额 / 持仓 / 我的订单 + 本地影子订单。
 *
 * <p>设计约束：</p>
 * <ol>
 *   <li><b>只读</b>。本类不调用任何下单、撤单、改杠杆接口，也不改本地账本；</li>
 *   <li>凭证只从配置（最终来源是环境变量）读取，响应里只出现脱敏后的 apiKey，secret/passphrase 只报
 *       "已配置 / 未配置"；</li>
 *   <li>上游失败不抛 500：统一折成 {@code error} 块，前端账户页可以把"凭证没配 / 权限不足 / 限流"
 *       直接展示给用户，而不是一个空白页；</li>
 *   <li>订单分三种来源：{@code local}（okx_shadow_order 本地影子账本）、{@code pending}（交易所当前挂单）、
 *       {@code history}（交易所历史订单）。</li>
 * </ol>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public static final String STATE_LOCAL = "local";
    public static final String STATE_PENDING = "pending";
    public static final String STATE_HISTORY = "history";

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int OKX_MAX_LIMIT = 100;

    private final OkxProperties properties;
    /** 当前生效凭证（环境变量或库内密文解密后的内存快照）。 */
    private final OkxCredentials okxCredentials;
    private final OkxTradeClient tradeClient;
    private final ShadowRepository shadowRepository;
    private final String keyLabel;

    public AccountService(OkxProperties properties, OkxCredentials okxCredentials, OkxTradeClient tradeClient,
            ShadowRepository shadowRepository, @Value("${okx.account.key-label:OkexAlpha}") String keyLabel) {
        this.properties = properties;
        this.okxCredentials = okxCredentials;
        this.tradeClient = tradeClient;
        this.shadowRepository = shadowRepository;
        this.keyLabel = keyLabel == null || keyLabel.isBlank() ? "OkexAlpha" : keyLabel.trim();
    }

    /** 账户页头部：凭证就绪状态（脱敏）+ 交易所账户配置。 */
    public Map<String, Object> summary() {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", normalizeMode(trading.mode()));
        out.put("configured", credentialsReady());
        out.put("credentials", credentials());
        out.put("fetchedAt", Instant.now().toString());
        if (!credentialsReady()) {
            out.put("notice", "未配置完整凭证（api-key / secret-key / passphrase 三者都要有），"
                    + "账户查询不可用；当前只能看本地影子账本。凭证可以用 POST /api/v1/account/credentials "
                    + "加密入库（需要先设置 OKX_MASTER_KEY），或继续用环境变量 OKX_API_KEY / "
                    + "OKX_SECRET_KEY / OKX_PASSPHRASE 注入；不要写进代码或提交到 git。");
            return out;
        }
        guarded(out, "account", () -> {
            List<JsonNode> nodes = tradeClient.accountConfig();
            if (nodes.isEmpty()) {
                return null;
            }
            JsonNode node = nodes.get(0);
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("uid", SecretMasker.maskMiddle(text(node, "uid")));
            account.put("mainUid", SecretMasker.maskMiddle(text(node, "mainUid")));
            account.put("acctLv", text(node, "acctLv"));
            account.put("posMode", text(node, "posMode"));
            account.put("acctStpMode", text(node, "acctStpMode"));
            account.put("autoLoan", text(node, "autoLoan"));
            account.put("perm", text(node, "perm"));
            account.put("kycLv", text(node, "kycLv"));
            account.put("level", text(node, "level"));
            account.put("levelTmp", text(node, "levelTmp"));
            out.put("account", account);
            return null;
        });
        out.put("permissionNote", "只读查询：本服务不会用这把 Key 下单/撤单/划转；请在 OKX 后台按最小权限收紧。");
        return out;
    }

    /** 账户余额（多币种）。 */
    public Map<String, Object> balance() {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        Map<String, Object> out = emptyEnvelope(trading, "balance");
        if (!credentialsReady()) {
            return out;
        }
        guarded(out, "balance", () -> {
            List<JsonNode> nodes = tradeClient.balance();
            if (nodes.isEmpty()) {
                out.put("totalEq", null);
                out.put("details", List.of());
                return null;
            }
            JsonNode node = nodes.get(0);
            out.put("totalEq", text(node, "totalEq"));
            out.put("isoEq", text(node, "isoEq"));
            out.put("adjEq", text(node, "adjEq"));
            out.put("availEq", text(node, "availEq"));
            out.put("mgnRatio", text(node, "mgnRatio"));
            out.put("imr", text(node, "imr"));
            out.put("mmr", text(node, "mmr"));
            out.put("notionalUsd", text(node, "notionalUsd"));
            out.put("uTime", text(node, "uTime"));
            List<Map<String, Object>> details = new ArrayList<>();
            JsonNode detailNodes = node.get("details");
            if (detailNodes != null && detailNodes.isArray()) {
                for (JsonNode detail : detailNodes) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ccy", text(detail, "ccy"));
                    row.put("eq", text(detail, "eq"));
                    row.put("eqUsd", text(detail, "eqUsd"));
                    row.put("cashBal", text(detail, "cashBal"));
                    row.put("availBal", text(detail, "availBal"));
                    row.put("availEq", text(detail, "availEq"));
                    row.put("frozenBal", text(detail, "frozenBal"));
                    row.put("ordFrozen", text(detail, "ordFrozen"));
                    row.put("upl", text(detail, "upl"));
                    row.put("uplLiab", text(detail, "uplLiab"));
                    row.put("disEq", text(detail, "disEq"));
                    details.add(row);
                }
            }
            out.put("details", details);
            return null;
        });
        return out;
    }

    /** 当前持仓（默认 SWAP 合约）。 */
    public Map<String, Object> positions(String instType) {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        Map<String, Object> out = emptyEnvelope(trading, "positions");
        String type = instType == null || instType.isBlank() ? "SWAP" : instType.trim().toUpperCase(Locale.ROOT);
        out.put("instType", type);
        if (!credentialsReady()) {
            return out;
        }
        guarded(out, "positions", () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode node : tradeClient.positions(type)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("instId", text(node, "instId"));
                row.put("instType", text(node, "instType"));
                row.put("posSide", text(node, "posSide"));
                row.put("pos", text(node, "pos"));
                row.put("availPos", text(node, "availPos"));
                row.put("avgPx", text(node, "avgPx"));
                row.put("markPx", text(node, "markPx"));
                row.put("upl", text(node, "upl"));
                row.put("uplRatio", text(node, "uplRatio"));
                row.put("lever", text(node, "lever"));
                row.put("liqPx", text(node, "liqPx"));
                row.put("mgnMode", text(node, "mgnMode"));
                row.put("notionalUsd", text(node, "notionalUsd"));
                row.put("margin", text(node, "margin"));
                row.put("mgnRatio", text(node, "mgnRatio"));
                row.put("adl", text(node, "adl"));
                row.put("cTime", text(node, "cTime"));
                row.put("uTime", text(node, "uTime"));
                rows.add(row);
            }
            out.put("items", rows);
            return null;
        });
        return out;
    }

    /**
     * 我的订单。
     *
     * @param state    local（本地影子账本，默认）/ pending（交易所当前挂单）/ history（交易所历史订单）
     * @param instType 仅对 pending/history 生效（SWAP/FUTURES/SPOT/MARGIN/OPTION）
     * @param instId   可选的单一合约过滤（pending/history 生效；local 直接用本地过滤）
     * @param limit    条数上限（本地最多 200，交易所最多 100）
     */
    public Map<String, Object> orders(String state, String instType, String instId, Integer limit) {
        String resolvedState = state == null || state.isBlank() ? STATE_LOCAL : state.trim().toLowerCase(Locale.ROOT);
        if (!STATE_LOCAL.equals(resolvedState) && !STATE_PENDING.equals(resolvedState)
                && !STATE_HISTORY.equals(resolvedState)) {
            throw new IllegalArgumentException("state must be one of local|pending|history");
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        Map<String, Object> out = emptyEnvelope(trading, resolvedState);
        out.put("instType", instType);
        out.put("instId", instId);
        out.put("limit", size);

        if (STATE_LOCAL.equals(resolvedState)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ShadowOrderRow order : shadowRepository.orders(null, instId, Math.min(size, MAX_LIMIT), 0)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "LOCAL");
                row.put("id", order.id());
                row.put("strategyId", order.strategyId());
                row.put("instId", order.instId());
                row.put("side", order.side());
                row.put("ordType", order.ordType());
                row.put("sz", order.sz());
                row.put("px", order.px());
                row.put("notionalUsd", order.notionalUsd());
                row.put("state", order.status());
                row.put("reason", order.reason());
                row.put("signalTsMs", order.signalTsMs());
                row.put("decidedAt", order.decidedAt() == null ? null : order.decidedAt().toString());
                rows.add(row);
            }
            out.put("items", rows);
            out.put("ok", true);
            return out;
        }

        if (!credentialsReady()) {
            return out;
        }
        int okxLimit = Math.min(size, OKX_MAX_LIMIT);
        out.put("limit", okxLimit);
        guarded(out, "orders", () -> {
            List<JsonNode> nodes = STATE_PENDING.equals(resolvedState)
                    ? tradeClient.pendingOrders(instType)
                    : tradeClient.ordersHistory(instType, instId, okxLimit);
            // orders-history 只保留最近 7 天；为空时回落到 3 个月归档，避免"明明有单却查不到"。
            if (nodes.isEmpty() && STATE_HISTORY.equals(resolvedState)) {
                nodes = tradeClient.ordersHistoryArchive(instType, instId, okxLimit);
                if (!nodes.isEmpty()) {
                    out.put("archive", true);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode node : nodes) {
                if (rows.size() >= okxLimit) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "OKX");
                row.put("ordId", text(node, "ordId"));
                row.put("clOrdId", text(node, "clOrdId"));
                row.put("instId", text(node, "instId"));
                row.put("instType", text(node, "instType"));
                row.put("tdMode", text(node, "tdMode"));
                row.put("side", text(node, "side"));
                row.put("posSide", text(node, "posSide"));
                row.put("ordType", text(node, "ordType"));
                row.put("sz", text(node, "sz"));
                row.put("px", text(node, "px"));
                row.put("avgPx", text(node, "avgPx"));
                row.put("accFillSz", text(node, "accFillSz"));
                // fillSz 是 accFillSz 的别名：前端表格统一读 fillSz，免得两套字段名。
                row.put("fillSz", text(node, "accFillSz"));
                row.put("state", text(node, "state"));
                row.put("lever", text(node, "lever"));
                row.put("fee", text(node, "fee"));
                row.put("feeCcy", text(node, "feeCcy"));
                row.put("pnl", text(node, "pnl"));
                row.put("reduceOnly", text(node, "reduceOnly"));
                row.put("algoId", text(node, "algoId"));
                row.put("cTime", text(node, "cTime"));
                row.put("uTime", text(node, "uTime"));
                row.put("cancelSource", text(node, "cancelSource"));
                rows.add(row);
            }
            out.put("items", rows);
            return null;
        });
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> emptyEnvelope(OkxProperties.Strategy.Trading trading, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        out.put("configured", credentialsReady());
        out.put("mode", normalizeMode(trading.mode()));
        out.put("credentials", credentials());
        // balance 没有列表语义，不放 items 占位；positions/orders 保留空数组便于前端直接遍历。
        if (!"balance".equals(state)) {
            out.put("items", List.of());
        }
        out.put("fetchedAt", Instant.now().toString());
        if (!credentialsReady() && !STATE_LOCAL.equals(state)) {
            out.put("notice", "未配置完整凭证（环境变量 OKX_API_KEY / OKX_SECRET_KEY / OKX_PASSPHRASE，"
                    + "或用 POST /api/v1/account/credentials 加密入库），交易所数据不可用。");
        }
        return out;
    }

    private Map<String, Object> credentials() {
        OkxCredentials.Credential current = okxCredentials.current();
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("label", current.label() == null ? keyLabel : current.label());
        credentials.put("apiKey", current.maskedApiKey());
        credentials.put("apiKeyConfigured", SecretMasker.configured(current.apiKey()));
        credentials.put("secretKeyConfigured", SecretMasker.configured(current.secretKey()));
        credentials.put("passphraseConfigured", SecretMasker.configured(current.passphrase()));
        credentials.put("source", current.source());
        return credentials;
    }

    /** 下游调用包一层：上游/网络错误折成 error 块，绝不让账户页 500。 */
    private void guarded(Map<String, Object> out, String section, Supplier<?> call) {
        try {
            call.get();
            out.put("ok", true);
        } catch (OkxApiException ex) {
            out.put("ok", false);
            out.put("error", errorOf(ex.kind().name(), ex.okxCode(), ex.getMessage()));
            log.warn("account {} query failed: {} {}", section, ex.kind(), ex.getMessage());
        } catch (RuntimeException ex) {
            out.put("ok", false);
            out.put("error", errorOf("INTERNAL", null, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            log.warn("account {} query failed", section, ex);
        }
    }

    private Map<String, Object> errorOf(String kind, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("kind", kind);
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        String hint = switch (kind) {
            case "MISSING_CREDENTIALS", "INVALID_PARAM" -> "检查 Key 的 passphrase / 权限 / IP 白名单是否与当前出口 IP 匹配。";
            case "RATE_LIMITED" -> "OKX 限流（50011），稍后重试。";
            case "NETWORK" -> "网络不可达或被 Cloudflare 拦截，稍后重试。";
            default -> null;
        };
        if (hint != null) {
            error.put("hint", hint);
        }
        return error;
    }

    private boolean credentialsReady() {
        return okxCredentials.current().ready();
    }

    private static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "SHADOW" : mode.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
