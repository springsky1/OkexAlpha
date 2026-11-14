package com.okexalpha.service.backfill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.domain.InstType;
import com.okexalpha.repository.InstrumentRepository;

/**
 * <p>按 24h 成交额挑 live SWAP 品种：一次 {@code /api/v5/market/tickers?instType=SWAP} 拉全量行情，
 * 用 {@code volCcy24h × last} 估算 USD 成交额，降序取前 {@code limit} 个，且只保留库内 live 的品种。</p>
 *
 * <p>SWAP 的 volCcy24h 是币本位数量（跨币种不可直接比），乘以 last 折成 USD 记名值才有可比性。
 * 字段缺失/非数字/非正数的条目直接剔除；剔除后一个不剩时返回空列表，由调用方决定退路
 * （增量同步此时退回字母序，行情接口抖动不拖垮整轮同步）。行情接口本身的失败直接向上抛。</p>
 */
@Component
public class TopVolumeSelector {

    private static final Logger log = LoggerFactory.getLogger(TopVolumeSelector.class);

    /** 库内 live SWAP 的取数上限（当前线上 500+，一万是够用的护栏）。 */
    private static final int LIVE_SCAN_LIMIT = 10_000;

    private final OkxApiClient client;
    private final InstrumentRepository instrumentRepository;

    public TopVolumeSelector(OkxApiClient client, InstrumentRepository instrumentRepository) {
        this.client = client;
        this.instrumentRepository = instrumentRepository;
    }

    /**
     * 返回 24h USD 成交额最大的前 {@code limit} 个 live SWAP instId（成交额并列按 instId 升序，保证确定性）。
     * 行情接口失败向上抛；全部条目不可用时返回空列表。
     */
    public List<String> select(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> live = new LinkedHashSet<>(
                instrumentRepository.findInstIds(InstType.SWAP.code(), null, LIVE_SCAN_LIMIT));
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode node : client.getTickers(InstType.SWAP.code())) {
            String instId = node.path("instId").asString(null);
            double usdVolume = positive(node.path("volCcy24h").asString(null))
                    * positive(node.path("last").asString(null));
            if (instId == null || usdVolume <= 0 || !live.contains(instId)) {
                continue;
            }
            candidates.add(new Candidate(instId, usdVolume));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::usdVolume).reversed()
                .thenComparing(Candidate::instId));
        List<String> top = new ArrayList<>(Math.min(limit, candidates.size()));
        for (Candidate candidate : candidates) {
            if (top.size() >= limit) {
                break;
            }
            top.add(candidate.instId());
        }
        log.debug("top-volume selection: {} live swaps, {} scored, top={}", live.size(), candidates.size(), top);
        return top;
    }

    private record Candidate(String instId, double usdVolume) {
    }

    /** 解析为正的有限 double，否则 0（0 会在调用处把整条行情剔除）。 */
    private static double positive(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(text);
            return value > 0 && Double.isFinite(value) ? value : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
