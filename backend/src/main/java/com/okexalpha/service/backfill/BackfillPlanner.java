package com.okexalpha.service.backfill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.Bar;
import com.okexalpha.domain.DataType;
import com.okexalpha.domain.InstType;
import com.okexalpha.domain.KlineType;
import com.okexalpha.domain.Period;
import com.okexalpha.domain.RatioType;
import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.repository.BackfillTaskSpec;
import com.okexalpha.repository.InstrumentRepository;

/** 把补拉请求展开成一组可独立执行、可续跑的任务。 */
@Component
public class BackfillPlanner {

    private static final long DAY_MS = 86_400_000L;
    private static final long DEFAULT_KLINE_DAYS = 7L;
    private static final long DEFAULT_FUNDING_DAYS = 90L;

    private final OkxProperties properties;
    private final InstrumentRepository instrumentRepository;
    private final ObjectMapper objectMapper;

    public BackfillPlanner(OkxProperties properties, InstrumentRepository instrumentRepository,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.instrumentRepository = instrumentRepository;
        this.objectMapper = objectMapper;
    }

    public List<BackfillTaskSpec> planKlines(KlineBackfillRequest request) {
        List<String> bars = requireNonEmpty(request.bars(), "bars");
        List<String> klineTypes = requireNonEmpty(request.klineTypes(), "klineTypes");
        long endMs = request.endMs() != null ? request.endMs() : System.currentTimeMillis();
        long startMs = request.startMs() != null ? request.startMs() : endMs - DEFAULT_KLINE_DAYS * DAY_MS;
        validateRange(startMs, endMs);
        List<String> instIds = resolveInstIds(request.instIds(), request.instType(), request.baseCcy());
        int pageLimit = pageLimit(request.pageLimit());

        List<BackfillTaskSpec> tasks = new ArrayList<>();
        for (String instId : instIds) {
            String instType = lookupInstType(instId);
            requirePerpetualSwap(instId, instType);
            for (String barCode : bars) {
                Bar bar = Bar.fromCode(barCode);
                for (String klineTypeCode : klineTypes) {
                    KlineType type = KlineType.fromCode(klineTypeCode);
                    TaskParams params = new TaskParams(DataType.KLINE.name(), instId, instType, bar.code(),
                            type.name(), null, null, null, null, null, null, startMs, endMs, pageLimit);
                    tasks.add(spec("KLINE:" + type.name() + ":" + instId + ":" + bar.code(), params));
                }
            }
        }
        return cap(tasks);
    }

    public List<BackfillTaskSpec> planFundingRate(FundingBackfillRequest request) {
        long endMs = request.endMs() != null ? request.endMs() : System.currentTimeMillis();
        long startMs = request.startMs() != null ? request.startMs() : endMs - DEFAULT_FUNDING_DAYS * DAY_MS;
        validateRange(startMs, endMs);
        List<String> instIds = resolveInstIds(request.instIds(), request.instType(), null);

        List<BackfillTaskSpec> tasks = new ArrayList<>();
        for (String instId : instIds) {
            String instType = lookupInstType(instId);
            TaskParams params = new TaskParams(DataType.FUNDING_RATE.name(), instId, instType, null, null, null,
                    null, null, null, null, null, startMs, endMs, null);
            tasks.add(spec("FUNDING:" + instId, params));
        }
        return cap(tasks);
    }

    public List<BackfillTaskSpec> planRatios(RatioBackfillRequest request) {
        List<String> ratioTypes = requireNonEmpty(request.ratioTypes(), "ratioTypes");
        List<String> periods = requireNonEmpty(request.periods(), "periods");
        long endMs = request.endMs() != null ? request.endMs() : System.currentTimeMillis();

        List<BackfillTaskSpec> tasks = new ArrayList<>();
        for (String ratioTypeCode : ratioTypes) {
            RatioType ratioType = RatioType.fromCode(ratioTypeCode);
            List<String> scopes = ratioType.scopeKind() == RatioType.ScopeKind.CCY
                    ? requireNonEmpty(request.ccys(), "ccys")
                    : requireNonEmpty(request.scopes(), "scopes");
            for (String scope : scopes) {
                for (String periodCode : periods) {
                    Period period = Period.fromCode(periodCode);
                    long startMs = resolveStart(request.startMs(), endMs, period);
                    validateRange(startMs, endMs);
                    TaskParams params = new TaskParams(DataType.LONG_SHORT_RATIO.name(), null, null, null, null,
                            ratioType.name(), scope, ratioType.scopeKind().name(), null, period.code(), null,
                            startMs, endMs, null);
                    tasks.add(spec("RATIO:" + ratioType.name() + ":" + scope + ":" + period.code(), params));
                }
            }
        }
        return cap(tasks);
    }

    public List<BackfillTaskSpec> planMarketStats(MarketStatsBackfillRequest request) {
        List<String> kinds = request.kinds() == null || request.kinds().isEmpty()
                ? List.of(DataType.OPEN_INTEREST_VOLUME.name(), DataType.TAKER_VOLUME.name(),
                        DataType.OPEN_INTEREST.name())
                : request.kinds();
        List<String> periods = request.periods() == null || request.periods().isEmpty()
                ? List.of(Period.H1.code())
                : request.periods();
        long endMs = request.endMs() != null ? request.endMs() : System.currentTimeMillis();

        List<BackfillTaskSpec> tasks = new ArrayList<>();
        for (String kindCode : kinds) {
            DataType kind = parseKind(kindCode);
            switch (kind) {
                case OPEN_INTEREST_VOLUME -> {
                    for (String ccy : requireNonEmpty(request.ccys(), "ccys")) {
                        for (String periodCode : periods) {
                            Period period = Period.fromCode(periodCode);
                            long startMs = resolveStart(request.startMs(), endMs, period);
                            validateRange(startMs, endMs);
                            TaskParams params = new TaskParams(kind.name(), null, null, null, null, null, null,
                                    null, ccy.toUpperCase(Locale.ROOT), period.code(), null, startMs, endMs, null);
                            tasks.add(spec("OI_VOLUME:" + ccy.toUpperCase(Locale.ROOT) + ":" + period.code(),
                                    params));
                        }
                    }
                }
                case TAKER_VOLUME -> {
                    for (String periodCode : periods) {
                        Period period = Period.fromCode(periodCode);
                        long startMs = resolveStart(request.startMs(), endMs, period);
                        validateRange(startMs, endMs);
                        for (String ccy : nullSafe(request.ccys())) {
                            TaskParams params = new TaskParams(kind.name(), null, null, null, null, null,
                                    ccy.toUpperCase(Locale.ROOT), "CCY", ccy.toUpperCase(Locale.ROOT), period.code(),
                                    "CONTRACTS", startMs, endMs, null);
                            tasks.add(spec("TAKER_VOLUME:CCY:" + ccy.toUpperCase(Locale.ROOT) + ":"
                                    + period.code(), params));
                        }
                        for (String instId : nullSafe(request.instIds())) {
                            String instType = lookupInstType(instId);
                            TaskParams params = new TaskParams(kind.name(), instId, instType, null,
                                    null, null, instId, "INST", null, period.code(), takerMarketType(instType),
                                    startMs, endMs, null);
                            tasks.add(spec("TAKER_VOLUME:INST:" + instId + ":" + period.code(), params));
                        }
                    }
                }
                case OPEN_INTEREST -> {
                    List<String> instIds = request.instIds() != null && !request.instIds().isEmpty()
                            ? request.instIds()
                            : instrumentRepository.findInstIds(InstType.SWAP.code(), null,
                                    properties.backfill().maxTasksPerJob());
                    if (instIds.isEmpty()) {
                        throw new NotFoundException("no instruments for open-interest snapshot");
                    }
                    long startMs = request.startMs() != null ? request.startMs() : endMs;
                    for (String instId : instIds) {
                        TaskParams params = new TaskParams(kind.name(), instId, lookupInstType(instId), null, null,
                                null, null, null, null, null, null, startMs, endMs, null);
                        tasks.add(spec("OPEN_INTEREST:" + instId, params));
                    }
                }
                default -> throw new BadRequestException("unsupported market-stats kind: " + kindCode);
            }
        }
        return cap(tasks);
    }

    private DataType parseKind(String code) {
        DataType kind;
        try {
            kind = DataType.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("unknown kind: " + code
                    + " (expected OPEN_INTEREST_VOLUME, TAKER_VOLUME, OPEN_INTEREST)");
        }
        return kind;
    }

    /** 未显式给 startMs 时，按该 period 的实际保留期取满，避免默认就落在保留边界之外。 */
    private long resolveStart(Long requestedStart, long endMs, Period period) {
        return requestedStart != null ? requestedStart : endMs - period.retentionMs();
    }

    /** K 线只做永续合约（okx_instrument.inst_type = SWAP）；现货/交割/期权/指数一律拒绝。 */
    private void requirePerpetualSwap(String instId, String instType) {
        if (!InstType.SWAP.code().equals(instType)) {
            throw new BadRequestException("klines only support perpetual swap instruments (instType=SWAP), got "
                    + instId + (instType == null
                            ? " (instrument not found in okx_instrument, run POST /api/v1/sync/instruments first)"
                            : " (instType=" + instType + ")"));
        }
    }

    /** inst 口径的 taker-volume 只覆盖合约；market_type 记录该 instId 所属市场，供唯一键与查询使用。 */
    private String takerMarketType(String instType) {
        if (instType == null) {
            return "";
        }
        return InstType.fromCode(instType).isDerivative() ? "CONTRACTS" : "SPOT";
    }

    private String lookupInstType(String instId) {
        InstrumentRow row = instrumentRepository.findById(instId);
        return row == null ? null : row.instType();
    }

    private List<String> resolveInstIds(List<String> instIds, String instType, String baseCcy) {
        boolean hasIds = instIds != null && !instIds.isEmpty();
        boolean hasType = instType != null && !instType.isBlank();
        if (hasIds && hasType) {
            throw new BadRequestException("instIds and instType are mutually exclusive");
        }
        if (hasIds) {
            return new ArrayList<>(new LinkedHashSet<>(instIds));
        }
        if (!hasType) {
            throw new BadRequestException("either instIds or instType is required");
        }
        InstType type = InstType.fromCode(instType);
        List<String> resolved = instrumentRepository.findInstIds(type.code(), baseCcy,
                properties.backfill().maxTasksPerJob());
        if (resolved.isEmpty()) {
            throw new NotFoundException("no instruments matched instType=" + instType
                    + (baseCcy == null ? "" : ", baseCcy=" + baseCcy));
        }
        return resolved;
    }

    private int pageLimit(Integer requested) {
        int configured = properties.backfill().pageLimit();
        int limit = requested != null ? requested : configured;
        if (limit < 1) {
            throw new BadRequestException("pageLimit must be >= 1");
        }
        return Math.min(limit, 300);
    }

    private void validateRange(long startMs, long endMs) {
        if (startMs >= endMs) {
            throw new BadRequestException("startMs must be earlier than endMs");
        }
    }

    private List<String> requireNonEmpty(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new BadRequestException(name + " is required");
        }
        return values;
    }

    private List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<BackfillTaskSpec> cap(List<BackfillTaskSpec> tasks) {
        int max = properties.backfill().maxTasksPerJob();
        if (tasks.size() > max) {
            throw new UnprocessableException("request expands to " + tasks.size()
                    + " tasks which exceeds okx.backfill.max-tasks-per-job=" + max
                    + "; narrow the time range or instIds");
        }
        return tasks;
    }

    private BackfillTaskSpec spec(String taskKey, TaskParams params) {
        try {
            return new BackfillTaskSpec(taskKey, objectMapper.writeValueAsString(params), params.startMs(),
                    params.endMs());
        } catch (JacksonException ex) {
            throw new IllegalStateException("cannot serialize task params for " + taskKey, ex);
        }
    }
}
