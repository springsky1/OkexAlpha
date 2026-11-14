package com.okexalpha.service.strategy.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.service.strategy.shadow.ShadowEngine;
import com.okexalpha.web.dto.LiveLeverageRequest;
import com.okexalpha.web.dto.LiveOrderRequest;

import tools.jackson.databind.JsonNode;

/**
 * 实盘门禁 + 受控下单（阶段 C / P4）。
 *
 * <p>这一层是"策略 → 交易所"的唯一出口，顺序固定：</p>
 * <ol>
 *   <li>先算门禁（{@link TradingGate#evaluate()}），不通过直接 422，不会触碰网络；</li>
 *   <li>REAL 模式要求 confirm 口令，防止误操作；</li>
 *   <li>下单请求带 clOrdId（幂等键），成交结果写进 okx_shadow_order 做审计（reason=LIVE_ORDER）；</li>
 *   <li>绝不做自动重试：下单重试可能重复成交。</li>
 * </ol>
 */
@Service
public class LiveTradingService {

    private static final Logger log = LoggerFactory.getLogger(LiveTradingService.class);

    /** REAL 模式下的二次确认口令。 */
    public static final String CONFIRM_REAL = "CONFIRM-REAL-TRADING";

    private final OkxProperties properties;
    private final OkxCredentials credentials;
    private final TradingGate gate;
    private final OkxTradeClient tradeClient;
    private final ShadowRepository repository;
    private final ShadowEngine shadowEngine;

    public LiveTradingService(OkxProperties properties, OkxCredentials credentials, TradingGate gate,
            OkxTradeClient tradeClient, ShadowRepository repository, ShadowEngine shadowEngine) {
        this.properties = properties;
        this.credentials = credentials;
        this.gate = gate;
        this.tradeClient = tradeClient;
        this.repository = repository;
        this.shadowEngine = shadowEngine;
    }

    public GateReport report() {
        return gate.evaluate();
    }

    /** 交易所账户快照（余额 + 持仓）；未配置密钥时明确返回 configured=false，不做静默吞错。 */
    public Map<String, Object> account() {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean configured = credentials.current().ready();
        out.put("configured", configured);
        out.put("credentialSource", credentials.current().source());
        out.put("mode", TradingGate.normalizeMode(trading.mode()));
        if (!configured) {
            out.put("reason", "未配置 API 密钥（环境变量 OKX_API_KEY/OKX_SECRET_KEY/OKX_PASSPHRASE，"
                    + "或 POST /api/v1/account/credentials 加密入库），当前只有本地影子账本可用");
            out.put("balance", List.of());
            out.put("positions", List.of());
            return out;
        }
        out.put("balance", tradeClient.balance());
        out.put("positions", tradeClient.positions("SWAP"));
        return out;
    }

    /**
     * 受控下单。返回结构包含本次门禁快照，便于审计。
     *
     * @throws UnprocessableException 门禁未通过（422，不会发出任何请求）
     * @throws BadRequestException    参数不合法或 REAL 模式缺少确认口令
     */
    public Map<String, Object> placeOrder(LiveOrderRequest request) {
        if (request == null || request.instId() == null || request.instId().isBlank()) {
            throw new BadRequestException("instId is required");
        }
        String side = request.side() == null ? "" : request.side().trim().toLowerCase(Locale.ROOT);
        if (!"buy".equals(side) && !"sell".equals(side)) {
            throw new BadRequestException("side must be buy or sell");
        }
        if (request.sz() == null || request.sz() <= 0d) {
            throw new BadRequestException("sz must be > 0 (contracts, not USD)");
        }
        String posSide = request.posSide() == null ? "" : request.posSide().trim().toLowerCase(Locale.ROOT);
        if (!posSide.isEmpty() && !"long".equals(posSide) && !"short".equals(posSide)) {
            throw new BadRequestException("posSide must be long, short or empty");
        }
        String ordType = request.ordType() == null || request.ordType().isBlank() ? "market"
                : request.ordType().trim().toLowerCase(Locale.ROOT);
        if (!"market".equals(ordType) && !"limit".equals(ordType)) {
            throw new BadRequestException("ordType must be market or limit");
        }
        if ("limit".equals(ordType) && (request.px() == null || request.px() <= 0d)) {
            throw new BadRequestException("px is required for limit orders");
        }

        GateReport report = gate.evaluate();
        if (!report.liveAllowed()) {
            throw new UnprocessableException("live trading blocked -> " + String.join(" | ", report.blockers()));
        }
        if ("REAL".equals(report.mode()) && !CONFIRM_REAL.equals(request.confirm())) {
            throw new BadRequestException("mode=REAL requires confirm=\"" + CONFIRM_REAL + "\"");
        }

        String clOrdId = "okxa" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", request.instId());
        body.put("tdMode", request.tdMode() == null || request.tdMode().isBlank() ? "isolated" : request.tdMode());
        body.put("side", side);
        body.put("ordType", ordType);
        body.put("sz", formatSz(request.sz()));
        // 双向持仓（long_short_mode）下 OKX 要求 posSide；净持仓模式必须不带该字段。
        if (!posSide.isEmpty()) {
            body.put("posSide", posSide);
        }
        body.put("clOrdId", clOrdId);
        if ("limit".equals(ordType)) {
            body.put("px", formatSz(request.px()));
        }
        if (Boolean.TRUE.equals(request.reduceOnly())) {
            body.put("reduceOnly", true);
        }

        List<JsonNode> data = tradeClient.placeOrder(body);
        JsonNode first = data.isEmpty() ? null : data.get(0);
        String sCode = first == null ? null : text(first, "sCode");
        String sMsg = first == null ? null : text(first, "sMsg");
        String ordId = first == null ? null : text(first, "ordId");
        boolean accepted = sCode == null || "0".equals(sCode);

        double refPx = request.px() != null ? request.px() : priceOf(request.instId());
        double notional = request.sz() * refPx;
        repository.insertOrder(null, request.instId(), side, ordType, request.sz(), refPx, notional,
                accepted ? "FILLED" : "REJECTED", "LIVE_ORDER:" + (sCode == null ? "no-scode" : sCode),
                System.currentTimeMillis());
        log.info("live order submitted instId={} side={} sz={} ordType={} clOrdId={} sCode={}",
                request.instId(), side, request.sz(), ordType, clOrdId, sCode);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instId", request.instId());
        out.put("side", side);
        out.put("ordType", ordType);
        out.put("sz", request.sz());
        out.put("px", request.px());
        out.put("clOrdId", clOrdId);
        out.put("ordId", ordId);
        out.put("sCode", sCode);
        out.put("sMsg", sMsg);
        out.put("accepted", accepted);
        out.put("mode", report.mode());
        out.put("gate", GateCheckSnapshot.of(report));
        return out;
    }

    /**
     * 调整单个合约的杠杆（默认逐仓）。同样走门禁与 REAL 二次确认，避免杠杆被误改。
     *
     * <p>马丁格尔回测里「止损距离 = stopLossPct% ÷ leverage」，实盘开仓时 OKX 用的是该合约
     * 当前杠杆（往往是默认值），开完必须显式设回回测值，否则强平距离与报告不一致。</p>
     */
    public Map<String, Object> setLeverage(LiveLeverageRequest request) {
        if (request == null || request.instId() == null || request.instId().isBlank()) {
            throw new BadRequestException("instId is required");
        }
        String lever = request.lever() == null ? "" : request.lever().trim();
        double leverValue;
        try {
            leverValue = Double.parseDouble(lever);
        } catch (NumberFormatException ex) {
            throw new BadRequestException("lever must be a number, e.g. \"3\"");
        }
        if (leverValue < 1d || leverValue > 125d) {
            throw new BadRequestException("lever must be within 1..125");
        }
        String mgnMode = request.mgnMode() == null || request.mgnMode().isBlank() ? "isolated"
                : request.mgnMode().trim().toLowerCase(Locale.ROOT);
        if (!"isolated".equals(mgnMode) && !"cross".equals(mgnMode)) {
            throw new BadRequestException("mgnMode must be isolated or cross");
        }
        String posSide = request.posSide() == null ? "" : request.posSide().trim().toLowerCase(Locale.ROOT);
        if (!posSide.isEmpty() && !"long".equals(posSide) && !"short".equals(posSide)) {
            throw new BadRequestException("posSide must be long, short or empty");
        }
        GateReport report = gate.evaluate();
        if (!report.liveAllowed()) {
            throw new UnprocessableException("live trading blocked -> " + String.join(" | ", report.blockers()));
        }
        if ("REAL".equals(report.mode()) && !CONFIRM_REAL.equals(request.confirm())) {
            throw new BadRequestException("mode=REAL requires confirm=\"" + CONFIRM_REAL + "\"");
        }

        List<JsonNode> data = tradeClient.setLeverage(request.instId().trim(), lever, mgnMode, posSide);
        JsonNode first = data.isEmpty() ? null : data.get(0);
        String sCode = first == null ? null : text(first, "sCode");
        String sMsg = first == null ? null : text(first, "sMsg");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instId", request.instId().trim());
        out.put("lever", lever);
        out.put("mgnMode", mgnMode);
        out.put("posSide", posSide);
        out.put("sCode", sCode);
        out.put("sMsg", sMsg);
        out.put("accepted", sCode == null || "0".equals(sCode));
        out.put("mode", report.mode());
        out.put("gate", GateCheckSnapshot.of(report));
        log.info("live leverage set instId={} lever={} mgnMode={} sCode={}", out.get("instId"), lever, mgnMode,
                sCode);
        return out;
    }

    private double priceOf(String instId) {
        Double px = shadowEngine.lastPrice(instId);
        return px == null ? 0d : px;
    }

    private static String formatSz(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 下单响应里内嵌的门禁快照（只保留结论，避免响应过大）。 */
    record GateCheckSnapshot(String mode, boolean liveAllowed, List<String> blockers) {

        static GateCheckSnapshot of(GateReport report) {
            return new GateCheckSnapshot(report.mode(), report.liveAllowed(), report.blockers());
        }
    }
}
