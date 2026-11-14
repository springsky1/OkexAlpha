package com.okexalpha.service.strategy.live;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.security.OkxCredentials;

import tools.jackson.databind.JsonNode;

/**
 * 门禁用的「真实账户资金」视图：只读，带 15s TTL 缓存。
 *
 * <p>实盘门禁原先只看本地影子账本，真实账户哪怕余额为 0 也照样放行。这里把真实权益/可用资金/持仓数
 * 拉进同一份可审计的快照；交易所调用失败时返回 {@code available=false}，由门禁按 BLOCK 处理（fail-closed）。</p>
 */
@Component
public class RealAccountSource {

    private static final Logger log = LoggerFactory.getLogger(RealAccountSource.class);

    /** 缓存有效期：门禁可能在一次下单流程里被调用多次，避免重复打交易所。 */
    static final long TTL_MS = 15_000L;

    private final OkxTradeClient tradeClient;
    private final OkxCredentials credentials;

    private volatile Funds cached;
    private volatile long cachedAtMs;

    public RealAccountSource(OkxTradeClient tradeClient, OkxCredentials credentials) {
        this.tradeClient = tradeClient;
        this.credentials = credentials;
    }

    /**
     * 真实账户资金快照。
     *
     * @param available    是否成功读到（false 时下游必须 BLOCK）
     * @param equityUsd    账户总权益（USD）
     * @param availableUsd 可用保证金（USD）
     * @param positionCount 真实持仓数（SWAP）
     * @param fetchedAtMs  本次数据的时间戳
     * @param note         不可用时的原因
     */
    public record Funds(boolean available, double equityUsd, double availableUsd, int positionCount, long fetchedAtMs,
            String note) {

        public static Funds unavailable(String note) {
            return new Funds(false, 0d, 0d, 0, System.currentTimeMillis(), note);
        }
    }

    public Funds funds() {
        long now = System.currentTimeMillis();
        Funds snapshot = cached;
        if (snapshot != null && now - cachedAtMs < TTL_MS) {
            return snapshot;
        }
        Funds fresh = load();
        cached = fresh;
        cachedAtMs = now;
        return fresh;
    }

    private Funds load() {
        if (credentials == null || !credentials.current().ready()) {
            return Funds.unavailable("未配置完整 API 凭证，无法读取真实账户");
        }
        try {
            List<JsonNode> balances = tradeClient.balance();
            if (balances == null || balances.isEmpty()) {
                return Funds.unavailable("交易所返回空余额");
            }
            JsonNode node = balances.get(0);
            double equity = number(node, "totalEq");
            double available = number(node, "availEq");
            if (available <= 0d) {
                available = sumDetailAvailable(node);
            }
            int positions = 0;
            List<JsonNode> pos = tradeClient.positions("SWAP");
            if (pos != null) {
                positions = pos.size();
            }
            return new Funds(true, equity, available, positions, System.currentTimeMillis(), null);
        } catch (RuntimeException ex) {
            log.warn("读取真实账户资金失败，门禁将按不可用处理", ex);
            return Funds.unavailable("读取真实账户失败：" + ex.getMessage());
        }
    }

    /** OKX /account/balance 的 details[] 里逐币种可用余额（USD 计价）。 */
    private static double sumDetailAvailable(JsonNode node) {
        JsonNode details = node.get("details");
        if (details == null || !details.isArray()) {
            return 0d;
        }
        double total = 0d;
        for (JsonNode detail : details) {
            double usd = number(detail, "availEq");
            if (usd <= 0d) {
                double eqUsd = number(detail, "eqUsd");
                double availBal = number(detail, "availBal");
                double eq = number(detail, "eq");
                if (eqUsd > 0d && eq > 0d && availBal > 0d) {
                    usd = availBal / eq * eqUsd;
                }
            }
            if (usd > 0d) {
                total += usd;
            }
        }
        return total;
    }

    private static double number(JsonNode node, String field) {
        if (node == null) {
            return 0d;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0d;
        }
        String text = value.asString();
        if (text == null || text.isBlank()) {
            return 0d;
        }
        try {
            double parsed = Double.parseDouble(text.trim());
            return Double.isFinite(parsed) ? parsed : 0d;
        } catch (NumberFormatException ex) {
            return 0d;
        }
    }
}
