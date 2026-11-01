package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.repository.BackfillTaskSpec;
import com.okexalpha.repository.InstrumentRepository;

/** BackfillPlanner：任务展开、taskKey 命名、参数校验与任务数上限。 */
class BackfillPlannerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long DAY = 86_400_000L;
    private static final long END = 1_735_689_600_000L;
    private static final long START = END - DAY;

    private InstrumentRepository instruments;

    @BeforeEach
    void setUp() {
        instruments = mock(InstrumentRepository.class);
    }

    private BackfillPlanner planner() {
        return planner(OkxProperties.defaults());
    }

    private BackfillPlanner planner(OkxProperties properties) {
        return new BackfillPlanner(properties, instruments, MAPPER);
    }

    private static OkxProperties withBackfill(int pageLimit, int maxTasksPerJob) {
        OkxProperties base = OkxProperties.defaults();
        return new OkxProperties(base.baseUrl(), base.connectTimeoutMs(), base.readTimeoutMs(),
                base.userAgent(), base.retry(), base.rateLimit(),
                new OkxProperties.Backfill(base.backfill().workerThreads(), base.backfill().queueCapacity(),
                        pageLimit, base.backfill().autoResume(), maxTasksPerJob),
                base.sync(), base.schedule(), base.apiLog(), base.db());
    }

    static InstrumentRow instrument(String instId, String instType) {
        return new InstrumentRow(instId, instType, instId.replace("-USDT-SWAP", "-USDT"), null, "BTC", "USDT",
                "USDT", "linear", null, null, null, null, null, null, null, null, null, "live", null, null, null,
                null, null, "{}");
    }

    private static KlineBackfillRequest klines(List<String> instIds, String instType, List<String> bars,
            List<String> klineTypes, Long startMs, Long endMs, Integer pageLimit) {
        return new KlineBackfillRequest(instIds, instType, null, bars, klineTypes, startMs, endMs, pageLimit);
    }

    @Test
    void planKlinesBuildsExpectedTaskKeyAndParams() {
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner()
                .planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, null));

        assertThat(tasks).hasSize(1);
        BackfillTaskSpec spec = tasks.get(0);
        assertThat(spec.taskKey()).isEqualTo("KLINE:CANDLE:BTC-USDT-SWAP:1H");
        assertThat(spec.startMs()).isEqualTo(START);
        assertThat(spec.endMs()).isEqualTo(END);

        JsonNode params = MAPPER.readTree(spec.paramsJson());
        assertThat(params.get("dataType").asString()).isEqualTo("KLINE");
        assertThat(params.get("instId").asString()).isEqualTo("BTC-USDT-SWAP");
        assertThat(params.get("instType").asString()).isEqualTo("SWAP");
        assertThat(params.get("bar").asString()).isEqualTo("1H");
        assertThat(params.get("klineType").asString()).isEqualTo("CANDLE");
        assertThat(params.get("pageLimit").asInt(0)).isEqualTo(300);
    }

    @Test
    void planKlinesExpandsEveryBarAndKlineTypeCombination() {
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner().planKlines(klines(List.of("BTC-USDT-SWAP"), null,
                List.of("1H", "4H"), List.of("CANDLE", "MARK"), START, END, null));

        assertThat(tasks).extracting(BackfillTaskSpec::taskKey).containsExactly(
                "KLINE:CANDLE:BTC-USDT-SWAP:1H",
                "KLINE:MARK:BTC-USDT-SWAP:1H",
                "KLINE:CANDLE:BTC-USDT-SWAP:4H",
                "KLINE:MARK:BTC-USDT-SWAP:4H");
    }

    @Test
    void planKlinesRejectsBothInstIdsAndInstType() {
        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USDT-SWAP"), "SWAP", List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void planKlinesRequiresEitherInstIdsOrInstType() {
        assertThatThrownBy(() -> planner().planKlines(
                klines(null, null, List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("either instIds or instType is required");
    }

    @Test
    void planKlinesResolvesInstrumentsByInstType() {
        when(instruments.findInstIds(eq("SWAP"), eq(null), eq(5000)))
                .thenReturn(List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP"));
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner()
                .planKlines(klines(null, "SWAP", List.of("1H"), List.of("CANDLE"), START, END, null));

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(BackfillTaskSpec::taskKey)
                .containsExactly("KLINE:CANDLE:BTC-USDT-SWAP:1H", "KLINE:CANDLE:ETH-USDT-SWAP:1H");
    }

    @Test
    void planKlinesFailsWhenInstTypeMatchesNothing() {
        when(instruments.findInstIds(eq("SWAP"), eq(null), eq(5000))).thenReturn(List.of());

        assertThatThrownBy(() -> planner()
                .planKlines(klines(null, "SWAP", List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no instruments matched instType=SWAP");
    }

    @Test
    void planKlinesBuildsBothCandleAndMarkTasksForPerpetualSwap() {
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner().planKlines(
                klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE", "MARK"), START, END, null));

        assertThat(tasks).extracting(BackfillTaskSpec::taskKey).containsExactly(
                "KLINE:CANDLE:BTC-USDT-SWAP:1H",
                "KLINE:MARK:BTC-USDT-SWAP:1H");
        assertThat(MAPPER.readTree(tasks.get(1).paramsJson()).get("klineType").asString()).isEqualTo("MARK");
    }

    @Test
    void klinesRejectSpotInstrument() {
        when(instruments.findById("BTC-USDT")).thenReturn(instrument("BTC-USDT", "SPOT"));

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USDT"), null, List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("klines only support perpetual swap instruments (instType=SWAP)")
                .hasMessageContaining("got BTC-USDT (instType=SPOT)");
    }

    @Test
    void klinesRejectDeliveryFuturesInstrument() {
        when(instruments.findById("BTC-USD-261225")).thenReturn(instrument("BTC-USD-261225", "FUTURES"));

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USD-261225"), null, List.of("1H"), List.of("MARK"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("instType=SWAP")
                .hasMessageContaining("got BTC-USD-261225 (instType=FUTURES)");
    }

    @Test
    void klinesRejectOptionInstrument() {
        when(instruments.findById("BTC-USD-261225-C")).thenReturn(instrument("BTC-USD-261225-C", "OPTION"));

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USD-261225-C"), null, List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("instType=SWAP")
                .hasMessageContaining("instType=OPTION");
    }

    @Test
    void klinesRejectInstrumentMissingFromInstrumentTable() {
        when(instruments.findById("NOPE-USDT-SWAP")).thenReturn(null);

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("NOPE-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("instType=SWAP")
                .hasMessageContaining("got NOPE-USDT-SWAP (instrument not found in okx_instrument")
                .hasMessageContaining("POST /api/v1/sync/instruments");
    }

    @Test
    void klinesRejectInstTypeSwapWhenInstrumentRowIsMissing() {
        when(instruments.findInstIds(eq("SWAP"), eq(null), eq(5000)))
                .thenReturn(List.of("BTC-USDT-SWAP", "GHOST-USDT-SWAP"));
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));
        when(instruments.findById("GHOST-USDT-SWAP")).thenReturn(null);

        assertThatThrownBy(() -> planner()
                .planKlines(klines(null, "SWAP", List.of("1H"), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("got GHOST-USDT-SWAP (instrument not found in okx_instrument");
    }

    @Test
    void klinesRejectRemovedIndexKlineType() {
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("INDEX"), START, END, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid klineType: INDEX")
                .hasMessageContaining("allowed: CANDLE,MARK");
    }

    @Test
    void rejectsInvertedOrEmptyTimeRange() {
        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), END, START, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("startMs must be earlier than endMs");

        assertThatThrownBy(() -> planner().planKlines(
                klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, START, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("startMs must be earlier than endMs");
    }

    @Test
    void requiresBarsAndKlineTypes() {
        assertThatThrownBy(() -> planner()
                .planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of(), List.of("CANDLE"), START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bars is required");

        assertThatThrownBy(() -> planner()
                .planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), null, START, END, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("klineTypes is required");
    }

    @Test
    void pageLimitIsClampedToApiMaximum() {
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        BackfillTaskSpec spec = planner()
                .planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, 999))
                .get(0);

        assertThat(MAPPER.readTree(spec.paramsJson()).get("pageLimit").asInt()).isEqualTo(300);
    }

    @Test
    void pageLimitBelowOneIsRejected() {
        assertThatThrownBy(() -> planner()
                .planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, 0)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pageLimit must be >= 1");
    }

    @Test
    void taskExpansionBeyondConfiguredLimitIsUnprocessable() {
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        assertThatThrownBy(() -> planner(withBackfill(300, 2)).planKlines(klines(
                List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP"), null, List.of("1H", "4H"), List.of("CANDLE"), START, END,
                null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("exceeds okx.backfill.max-tasks-per-job=2");
    }

    @Test
    void taskExpansionAtTheLimitIsAccepted() {
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner(withBackfill(300, 2)).planKlines(klines(
                List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, null));

        assertThat(tasks).hasSize(2);
    }

    @Test
    void planFundingRateDefaultsToNinetyDayWindow() {
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner()
                .planFundingRate(new FundingBackfillRequest(List.of("BTC-USDT-SWAP"), null, null, END));

        assertThat(tasks).extracting(BackfillTaskSpec::taskKey).containsExactly("FUNDING:BTC-USDT-SWAP");
        assertThat(tasks.get(0).startMs()).isEqualTo(END - 90 * DAY);
        JsonNode params = MAPPER.readTree(tasks.get(0).paramsJson());
        assertThat(params.get("dataType").asString()).isEqualTo("FUNDING_RATE");
    }

    @Test
    void planRatiosRequiresCcysForCcyScopedTypes() {
        assertThatThrownBy(() -> planner().planRatios(new RatioBackfillRequest(
                List.of("LSR_ACCOUNT_CCY"), List.of(), null, List.of("1H"), START, END)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ccys is required");
    }

    @Test
    void planRatiosRequiresScopesForInstrumentScopedTypes() {
        assertThatThrownBy(() -> planner().planRatios(new RatioBackfillRequest(
                List.of("LSR_ACCOUNT_CONTRACT"), null, List.of("BTC"), List.of("1H"), START, END)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scopes is required");
    }

    @Test
    void planRatiosBuildsScopeKeys() {
        List<BackfillTaskSpec> tasks = planner().planRatios(new RatioBackfillRequest(
                List.of("LSR_ACCOUNT_CONTRACT", "LSR_ACCOUNT_CCY"), List.of("BTC-USDT-SWAP"), List.of("btc"),
                List.of("1H", "1D"), START, END));

        assertThat(tasks).extracting(BackfillTaskSpec::taskKey).containsExactly(
                "RATIO:LSR_ACCOUNT_CONTRACT:BTC-USDT-SWAP:1H",
                "RATIO:LSR_ACCOUNT_CONTRACT:BTC-USDT-SWAP:1D",
                "RATIO:LSR_ACCOUNT_CCY:btc:1H",
                "RATIO:LSR_ACCOUNT_CCY:btc:1D");
    }

    @Test
    void planMarketStatsDefaultsKindsAndBuildsAllFamilies() {
        when(instruments.findById("BTC-USDT-SWAP")).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        List<BackfillTaskSpec> tasks = planner()
                .planMarketStats(new MarketStatsBackfillRequest(null, List.of("btc"), List.of("BTC-USDT-SWAP"),
                        List.of("1H"), START, END));

        assertThat(tasks).extracting(BackfillTaskSpec::taskKey).containsExactly(
                "OI_VOLUME:BTC:1H",
                "TAKER_VOLUME:CCY:BTC:1H",
                "TAKER_VOLUME:INST:BTC-USDT-SWAP:1H",
                "OPEN_INTEREST:BTC-USDT-SWAP");
    }

    @Test
    void planMarketStatsRejectsUnknownKind() {
        assertThatThrownBy(() -> planner().planMarketStats(
                new MarketStatsBackfillRequest(List.of("WHALES"), List.of("BTC"), null, List.of("1H"), START, END)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown kind: WHALES");
    }

    @Test
    void planMarketStatsOpenInterestNeedsInstruments() {
        when(instruments.findInstIds(eq("SWAP"), eq(null), eq(5000))).thenReturn(List.of());

        assertThatThrownBy(() -> planner().planMarketStats(
                new MarketStatsBackfillRequest(List.of("OPEN_INTEREST"), null, null, List.of("1H"), START, END)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no instruments for open-interest snapshot");
    }

    @Test
    void planMarketStatsUsesRetentionWindowWhenRangeOmitted() {
        List<BackfillTaskSpec> tasks = planner().planMarketStats(
                new MarketStatsBackfillRequest(List.of("OPEN_INTEREST_VOLUME"), List.of("BTC"), null, List.of("5m"),
                        null, END));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).startMs()).isEqualTo(END - 48L * 3_600_000L);
    }

    @Test
    void planKlinesDoesNotQueryInstrumentTableWhenIdsAreExplicit() {
        when(instruments.findById(any())).thenReturn(instrument("BTC-USDT-SWAP", "SWAP"));

        planner().planKlines(klines(List.of("BTC-USDT-SWAP"), null, List.of("1H"), List.of("CANDLE"), START, END, null));

        verify(instruments, never()).findInstIds(any(), any(), eq(5000));
    }
}