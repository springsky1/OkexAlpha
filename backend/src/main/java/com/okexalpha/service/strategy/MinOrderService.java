package com.okexalpha.service.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.MinOrderRepository;

import tools.jackson.databind.JsonNode;

/**
 * 最小下单量 / 下单张数换算（R5）。
 *
 * <p>统一口径：<b>按美元名义价值下单</b>，把"买 100 U"换算成平台合法的 sz（张数）。</p>
 * <ul>
 *   <li>一张合约的名义价值：线性合约（ctValCcy 为币，如 BTC-USDT-SWAP 的 ctVal=0.01 BTC）= ctVal × 价格；
 *       反向合约（ctValCcy=USD，如 BTC-USD-SWAP 的 ctVal=100）= ctVal 本身（已是美元）。</li>
 *   <li>平台最小下单金额：minNotionalUsd = minSz × notionalPerContractUsd。</li>
 *   <li>下单张数：sz = max(minSz, ceil(targetNotional / notionalPerContractUsd / lotSz) × lotSz)，
 *       即向上取整到 lotSz 的整数倍，且不低于 minSz。</li>
 * </ul>
 *
 * <p>数量取整一律走 BigDecimal.ROUND_CEILING，避免 0.1 + 0.2 这类浮点尾数导致的"少一档"。 </p>
 */
@Service
public class MinOrderService {

    private static final Logger log = LoggerFactory.getLogger(MinOrderService.class);

    /** tickers 里优先取的字段顺序：最新成交价 → 买一 → 卖一。 */
    private static final List<String> PRICE_FIELDS = List.of("last", "bidPx", "askPx", "markPx");

    private final OkxApiClient client;
    private final InstrumentRepository instrumentRepository;
    private final MinOrderRepository minOrderRepository;

    public MinOrderService(OkxApiClient client, InstrumentRepository instrumentRepository,
            MinOrderRepository minOrderRepository) {
        this.client = client;
        this.instrumentRepository = instrumentRepository;
        this.minOrderRepository = minOrderRepository;
    }

    /** 一次刷新：拉 SWAP 行情 + 品种表，重算 532 个合约的最小下单量。 */
    public RefreshResult refresh() {
        long startNanos = System.nanoTime();
        List<InstrumentRow> instruments = instrumentRepository.findAll("SWAP", null, "live", 2000, 0);
        Map<String, Double> prices = fetchPrices();
        List<MinOrderRow> rows = new ArrayList<>(instruments.size());
        int tradable = 0;
        for (InstrumentRow instrument : instruments) {
            MinOrderRow row = toRow(instrument, prices.get(instrument.instId()));
            if (row.tradable()) {
                tradable++;
            }
            rows.add(row);
        }
        int written = minOrderRepository.upsertAll(rows);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("min-order refreshed: instruments={} tradable={} priced={} durationMs={}",
                written, tradable, prices.size(), durationMs);
        return new RefreshResult(written, tradable, prices.size(), durationMs);
    }

    /** 单个合约按目标美元名义价值换算 sz；目标金额缺失时返回平台最小下单量（1 个 minSz）。 */
    public Sizing sizeFor(String instId, Double targetNotionalUsd) {
        MinOrderRow row = minOrderRepository.findById(instId);
        if (row == null) {
            return null;
        }
        double target = targetNotionalUsd == null || targetNotionalUsd <= 0
                ? (row.minNotionalUsd() == null ? 0 : row.minNotionalUsd())
                : targetNotionalUsd;
        Sizing sizing = computeSizing(target, row.notionalPerContractUsd(), row.lotSz(), row.minSz());
        return new Sizing(row.instId(), row.refPx(), sizing.notionalPerContractUsd(), sizing.minNotionalUsd(),
                sizing.size(), sizing.lots(), sizing.notionalUsd(), sizing.minSized(), sizing.tradable());
    }

    /** 一张合约的美元名义价值；缺少 ctVal 或价格为 0 时返回 null。 */
    public static Double notionalPerContractUsd(Double ctVal, String ctValCcy, Double px) {
        if (ctVal == null || ctVal <= 0) {
            return null;
        }
        if (isInverse(ctValCcy)) {
            // 反向合约：ctVal 已是美元，价格只影响 sz 对应的标的数量
            return ctVal;
        }
        if (px == null || px <= 0) {
            return null;
        }
        return ctVal * px;
    }

    /** 反向（币本位）合约：ctValCcy=USD，ctVal 本身就是美元名义价值；线性（U 本位）合约 ctValCcy 为币。 */
    private static boolean isInverse(String ctValCcy) {
        return "USD".equalsIgnoreCase(ctValCcy);
    }

    /**
     * 核心换算。
     *
     * @param targetNotionalUsd      目标名义价值（美元）
     * @param notionalPerContractUsd 一张合约的美元名义价值
     * @param lotSz                  张数步长（OKX lotSz，如 0.1/1）
     * @param minSz                  最小张数（OKX minSz）
     */
    public static Sizing computeSizing(double targetNotionalUsd, Double notionalPerContractUsd, Double lotSz,
            Double minSz) {
        double lot = lotSz == null || lotSz <= 0 ? 1d : lotSz;
        double min = minSz == null || minSz <= 0 ? lot : minSz;
        if (notionalPerContractUsd == null || notionalPerContractUsd <= 0) {
            return new Sizing(null, null, null, null, null, null, null, false, false);
        }
        double minNotional = min * notionalPerContractUsd;
        double rawLots = targetNotionalUsd <= 0 ? min : targetNotionalUsd / notionalPerContractUsd;
        BigDecimal step = BigDecimal.valueOf(lot);
        double rounded = BigDecimal.valueOf(rawLots)
                .divide(step, 0, RoundingMode.CEILING)
                .multiply(step)
                .doubleValue();
        boolean minSized = rounded < min;
        double size = Math.max(min, rounded);
        double notional = size * notionalPerContractUsd;
        return new Sizing(null, null, notionalPerContractUsd, minNotional, size, size / lot, notional, minSized, true);
    }

    /**
     * Sizing 结果。
     *
     * @param instId                 合约
     * @param refPx                  参考价（tickers 最新价）
     * @param notionalPerContractUsd 一张合约的美元名义价值
     * @param minNotionalUsd         平台最小下单金额（美元）
     * @param size                   换算后的 sz（张数，已是 lotSz 整数倍且 ≥ minSz）
     * @param lots                   size / lotSz
     * @param notionalUsd            size × notionalPerContractUsd
     * @param minSized               目标金额低于平台最小下单金额，已按 minSz 兜底
     * @param tradable               数据齐全可以下单
     */
    public record Sizing(String instId, Double refPx, Double notionalPerContractUsd, Double minNotionalUsd,
            Double size, Double lots, Double notionalUsd, boolean minSized, boolean tradable) {
    }

    public record RefreshResult(int instruments, int tradable, int priced, long durationMs) {
    }

    private MinOrderRow toRow(InstrumentRow instrument, Double px) {
        Double ctVal = toDouble(instrument.ctVal());
        Double notionalPerContract = notionalPerContractUsd(ctVal, instrument.ctValCcy(), px);
        Double lotSz = toDouble(instrument.lotSz());
        Double minSz = toDouble(instrument.minSz());
        Double minNotional = notionalPerContract == null || minSz == null ? null : minSz * notionalPerContract;
        Double minLots = minSz == null || lotSz == null || lotSz <= 0 ? null : minSz / lotSz;
        boolean tradable = notionalPerContract != null && notionalPerContract > 0 && minSz != null && minSz > 0
                && lotSz != null && lotSz > 0;
        String note = null;
        if (px == null) {
            note = "no ticker price";
        } else if (ctVal == null || ctVal <= 0) {
            note = "no ctVal";
        } else if (minSz == null || minSz <= 0) {
            note = "no minSz";
        } else if (lotSz == null || lotSz <= 0) {
            note = "no lotSz";
        }
        return new MinOrderRow(instrument.instId(), instrument.instType(), instrument.settleCcy(), ctVal,
                instrument.ctValCcy(), toDouble(instrument.ctMult()), lotSz, minSz, toDouble(instrument.tickSz()),
                px, notionalPerContract, minNotional, minLots, tradable, note);
    }

    private Map<String, Double> fetchPrices() {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (JsonNode node : client.getTickers("SWAP")) {
            String instId = node.path("instId").asString(null);
            if (instId == null) {
                continue;
            }
            for (String field : PRICE_FIELDS) {
                String text = node.path(field).asString(null);
                if (text != null && !text.isBlank()) {
                    try {
                        double value = Double.parseDouble(text);
                        if (value > 0) {
                            prices.put(instId, value);
                            break;
                        }
                    } catch (NumberFormatException ex) {
                        log.debug("bad price field {}={} for {}", field, text, instId);
                    }
                }
            }
        }
        return prices;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
