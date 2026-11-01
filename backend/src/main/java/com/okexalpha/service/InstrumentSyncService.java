package com.okexalpha.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.model.InstrumentMapper;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.InstType;
import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.repository.CoinRepository;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.SyncCursorRepository;

/** 拉取并落库 instruments，随后重建派生表 okx_coin。 */
@Service
public class InstrumentSyncService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentSyncService.class);
    private static final int BATCH = 500;
    private static final java.util.regex.Pattern USD_FAMILY =
            java.util.regex.Pattern.compile(".*-USD(_UM)?");
    private static final String OPTION_FAMILY_CURSOR_PREFIX = "option_family:";

    private final OkxApiClient client;
    private final InstrumentMapper instrumentMapper;
    private final InstrumentRepository instrumentRepository;
    private final CoinRepository coinRepository;
    private final SyncCursorRepository cursorRepository;
    private final OkxProperties properties;

    public InstrumentSyncService(OkxApiClient client, InstrumentMapper instrumentMapper,
            InstrumentRepository instrumentRepository, CoinRepository coinRepository,
            SyncCursorRepository cursorRepository, OkxProperties properties) {
        this.client = client;
        this.instrumentMapper = instrumentMapper;
        this.instrumentRepository = instrumentRepository;
        this.coinRepository = coinRepository;
        this.cursorRepository = cursorRepository;
        this.properties = properties;
    }

    public record SyncResult(int instrumentsUpserted, int coinsUpserted, long durationMs,
            Map<String, Integer> perType, List<String> optionFamilies) {
    }

    public SyncResult sync(List<String> instTypes, Boolean includeOptions, List<String> families) {
        long startedAt = System.currentTimeMillis();
        List<InstType> types = resolveTypes(instTypes);
        boolean withOptions = includeOptions != null ? includeOptions : properties.sync().includeOptions();

        List<InstrumentRow> rows = new ArrayList<>();
        Map<String, Integer> perType = new LinkedHashMap<>();
        Set<String> familySet = new LinkedHashSet<>();

        for (InstType type : types) {
            if (type == InstType.OPTION) {
                continue;
            }
            List<JsonNode> data = client.getInstruments(type.code(), null);
            int count = appendRows(data, rows, familySet);
            perType.put(type.code(), count);
            log.info("instruments {} -> {} rows", type.code(), count);
        }

        List<String> optionFamilies = new ArrayList<>();
        if (withOptions && types.contains(InstType.OPTION)) {
            if (families != null && !families.isEmpty()) {
                familySet.addAll(families);
            }
            if (familySet.isEmpty()) {
                familySet.addAll(instrumentRepository.distinctFamilies());
            }
            List<String> candidates = optionCandidates(familySet);
            int optionRows = 0;
            int skipped = 0;
            for (String family : candidates) {
                List<JsonNode> data;
                try {
                    data = client.getInstruments(InstType.OPTION.code(), family);
                } catch (OkxApiException ex) {
                    // 只有币本位家族（如 BTC-USD）才有期权；USDT/USDC 家族上游返回 51000，跳过即可。
                    if (ex.kind() == OkxApiException.Kind.INVALID_PARAM) {
                        skipped++;
                        log.debug("skip option family {}: {}", family, ex.getMessage());
                        continue;
                    }
                    throw ex;
                }
                optionFamilies.add(family);
                cursorRepository.set(OPTION_FAMILY_CURSOR_PREFIX + family, System.currentTimeMillis());
                optionRows += appendRows(data, rows, new LinkedHashSet<>());
            }
            perType.put(InstType.OPTION.code(), optionRows);
            log.info("instruments OPTION -> {} rows across {} families ({} candidates skipped)",
                    optionRows, optionFamilies.size(), skipped);
        }

        int upserted = 0;
        for (int from = 0; from < rows.size(); from += BATCH) {
            upserted += instrumentRepository.upsertAll(rows.subList(from, Math.min(from + BATCH, rows.size())));
        }
        int coins = coinRepository.rebuild();
        long duration = System.currentTimeMillis() - startedAt;
        log.info("instruments sync done: {} rows, {} coins, {} ms", upserted, coins, duration);
        return new SyncResult(upserted, coins, duration, perType, optionFamilies);
    }

    /**
     * 期权只存在于币本位家族（如 BTC-USD、BTC-USD_UM）。先用已知可用家族，再把明显的
     * USD 家族排在前面，避免对 USDT/USDC 家族逐个发起必然失败的请求（每次要占限量名额）。
     */
    private List<String> optionCandidates(Set<String> families) {
        List<String> known = new ArrayList<>();
        known.addAll(cursorRepository.all().keySet().stream()
                .filter(key -> key.startsWith(OPTION_FAMILY_CURSOR_PREFIX))
                .map(key -> key.substring(OPTION_FAMILY_CURSOR_PREFIX.length()))
                .sorted()
                .toList());
        List<String> usd = families.stream()
                .filter(f -> USD_FAMILY.matcher(f).matches())
                .sorted()
                .toList();
        List<String> rest = families.stream()
                .filter(f -> !USD_FAMILY.matcher(f).matches())
                .sorted()
                .toList();
        List<String> ordered = new ArrayList<>();
        for (String family : known) {
            if (families.contains(family) && !ordered.contains(family)) {
                ordered.add(family);
            }
        }
        for (String family : usd) {
            if (!ordered.contains(family)) {
                ordered.add(family);
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(rest);
        }
        return ordered;
    }

    private int appendRows(List<JsonNode> data, List<InstrumentRow> rows, Set<String> families) {
        int count = 0;
        for (JsonNode node : data) {
            InstrumentRow row = instrumentMapper.toRow(node);
            rows.add(row);
            if (row.instFamily() != null && !row.instFamily().isBlank()) {
                families.add(row.instFamily());
            }
            count++;
        }
        return count;
    }

    private List<InstType> resolveTypes(List<String> instTypes) {
        if (instTypes == null || instTypes.isEmpty()) {
            return List.of(InstType.values());
        }
        List<InstType> types = new ArrayList<>();
        for (String code : instTypes) {
            types.add(InstType.fromCode(code));
        }
        return types;
    }
}
