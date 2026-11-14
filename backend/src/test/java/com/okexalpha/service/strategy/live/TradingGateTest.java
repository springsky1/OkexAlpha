package com.okexalpha.service.strategy.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.config.OkxProperties;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.security.SecretMasker;

/** 门禁必须默认关闭：任何一项 BLOCK 都不放行。 */
class TradingGateTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private ShadowRepository repository;

    private RealAccountSource realAccount;

    @BeforeEach
    void setUp() {
        repository = mock(ShadowRepository.class);
        realAccount = mock(RealAccountSource.class);
        given(realAccount.funds()).willReturn(account(1_000d, 500d, 0, NOW.toEpochMilli()));
        given(repository.countOpenPositions()).willReturn(0L);
        given(repository.filledNotionalSince(anyLong())).willReturn(0d);
        given(repository.latestHealthFailCount()).willReturn(0L);
        given(repository.countRunningBackfills()).willReturn(0L);
        given(repository.latestKlineTs("1m")).willReturn(NOW.toEpochMilli() - 60_000L);
        given(repository.apiOutcomeCounts(anyInt())).willReturn(new long[] {100L, 0L});
    }

    @Test
    @DisplayName("默认配置（SHADOW + 无密钥）不允许下单")
    void defaultsBlockEverything() {
        GateReport report = gate(OkxProperties.defaults().strategy().trading()).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.mode()).isEqualTo("SHADOW");
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("MODE:"))
                .anyMatch(b -> b.startsWith("ENABLED:"))
                .anyMatch(b -> b.startsWith("CREDENTIALS:"));
    }

    @Test
    @DisplayName("DEMO + 开关打开 + 密钥齐全 → 放行")
    void demoWithCredentialsAllows() {
        GateReport report = gate(trading(true, "DEMO", "key", "secret", "pass", 50, 500d)).evaluate();
        assertThat(report.blockers()).isEmpty();
        assertThat(report.liveAllowed()).isTrue();
        assertThat(report.mode()).isEqualTo("DEMO");
        assertThat(report.caps()).containsEntry("maxPositions", 50);
    }

    @Test
    @DisplayName("缺任一密钥即阻断")
    void missingCredentialsBlocks() {
        GateReport report = gate(trading(true, "DEMO", "key", "  ", "pass", 50, 500d)).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("CREDENTIALS:"));
    }

    @Test
    @DisplayName("持仓数达到上限即阻断")
    void positionLimitBlocks() {
        given(repository.countOpenPositions()).willReturn(3L);
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 3, 500d)).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("POSITION_LIMIT:"));
    }

    @Test
    @DisplayName("每日名义用尽即阻断")
    void dailyNotionalBlocks() {
        given(repository.filledNotionalSince(anyLong())).willReturn(600d);
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("DAILY_NOTIONAL:"));
    }

    @Test
    @DisplayName("最近一轮体检有 FAIL 即阻断")
    void healthFailBlocks() {
        given(repository.latestHealthFailCount()).willReturn(2L);
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("HEALTH:"));
    }

    @Test
    @DisplayName("补拉在跑只 WARN 不阻断")
    void runningBackfillOnlyWarns() {
        given(repository.countRunningBackfills()).willReturn(2L);
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();
        assertThat(report.liveAllowed()).isTrue();
        assertThat(report.checks()).anyMatch(c -> "BACKFILL_LOAD".equals(c.id()) && GateCheck.WARN.equals(c.level()));
    }

    @Test
    @DisplayName("未知 mode 视为阻断")
    void unknownModeBlocks() {
        GateReport report = gate(trading(true, "MOON", "k", "s", "p", 50, 500d)).evaluate();
        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("MODE:"));
    }

    @Test
    @DisplayName("凭证只认内存快照：配置里没有 key 但库内已装载 → 密钥项通过")
    void credentialsComeFromSnapshotNotConfig() {
        GateReport report = gate(trading(true, "DEMO", null, null, null, 50, 500d),
                OkxCredentials.of("AAAABBBB-CCCC-DDDD-EEEE-FFFF00001111", "0123456789abcdef0123456789abcdef",
                        "passphrase-123"))
                .evaluate();

        assertThat(report.liveAllowed()).isTrue();
        assertThat(report.blockers()).noneMatch(b -> b.startsWith("CREDENTIALS:"));
    }

    @Test
    @DisplayName("配置里写了 key 但快照为空 → 仍然按缺密钥阻断（不再读配置文件）")
    void configKeysAreIgnoredWhenSnapshotEmpty() {
        GateReport report = gate(trading(true, "DEMO", "key", "secret", "pass", 50, 500d),
                OkxCredentials.empty()).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("CREDENTIALS:"));
    }

    @Test
    @DisplayName("读不到真实账户资金 → 阻断（fail-closed）")
    void realAccountUnavailableBlocks() {
        given(realAccount.funds()).willReturn(RealAccountSource.Funds.unavailable("凭证缺失"));
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("REAL_ACCOUNT:"));
    }

    @Test
    @DisplayName("真实权益 <= 0 → 阻断")
    void zeroEquityBlocks() {
        given(realAccount.funds()).willReturn(account(0d, 500d, 0, NOW.toEpochMilli()));
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("REAL_ACCOUNT:"));
    }

    @Test
    @DisplayName("真实可用保证金不足单笔 → 阻断（影子账本有额度也没用）")
    void insufficientAvailableMarginBlocks() {
        given(realAccount.funds()).willReturn(account(300d, 50d, 0, NOW.toEpochMilli()));
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("AVAILABLE_MARGIN:"));
    }

    @Test
    @DisplayName("真实持仓数达到上限 → 阻断（不看影子持仓数）")
    void realPositionLimitBlocks() {
        given(realAccount.funds()).willReturn(account(1_000d, 500d, 3, NOW.toEpochMilli()));
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 3, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("REAL_POSITION_LIMIT:"));
    }

    @Test
    @DisplayName("真实资金快照过期只 WARN 不阻断")
    void staleAccountOnlyWarns() {
        given(realAccount.funds()).willReturn(account(1_000d, 500d, 0, NOW.toEpochMilli() - 600_000L));
        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isTrue();
        assertThat(report.checks()).anyMatch(c -> "REAL_ACCOUNT".equals(c.id()) && GateCheck.WARN.equals(c.level()));
    }

    @Test
    @DisplayName("组合保证金：账户权益 100U 装不下单笔 100U 保证金 → PORTFOLIO_MARGIN 阻断")
    void portfolioMarginBlocksWhenEquityTooSmall() {
        given(realAccount.funds()).willReturn(account(100d, 100d, 0, NOW.toEpochMilli()));
        given(repository.totalOpenMargin()).willReturn(0d);

        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("PORTFOLIO_MARGIN:"));
    }

    @Test
    @DisplayName("组合保证金：已用 900U 时剩余额度只有 50U，第二笔 100U 保证金被拒")
    void portfolioMarginCountsExistingShadowUsage() {
        given(realAccount.funds()).willReturn(account(1_000d, 800d, 0, NOW.toEpochMilli()));
        given(repository.totalOpenMargin()).willReturn(900d);

        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.liveAllowed()).isFalse();
        assertThat(report.blockers()).anyMatch(b -> b.startsWith("PORTFOLIO_MARGIN:"));
        assertThat(report.checks()).anyMatch(c -> "PORTFOLIO_MARGIN".equals(c.id()) && GateCheck.BLOCK.equals(c.level()));
    }

    @Test
    @DisplayName("组合保证金：额度刚好够用（已用 840U / 上限 950U）→ 不阻断")
    void portfolioMarginAllowsWhenHeadroomFits() {
        given(realAccount.funds()).willReturn(account(1_000d, 800d, 0, NOW.toEpochMilli()));
        given(repository.totalOpenMargin()).willReturn(840d);

        GateReport report = gate(trading(true, "DEMO", "k", "s", "p", 50, 500d)).evaluate();

        assertThat(report.blockers()).noneMatch(b -> b.startsWith("PORTFOLIO_MARGIN:"));
    }

    private TradingGate gate(OkxProperties.Strategy.Trading trading) {
        return gate(trading, credentialsOf(trading));
    }

    private TradingGate gate(OkxProperties.Strategy.Trading trading, OkxCredentials credentials) {
        return new TradingGate(propsWithTrading(trading), credentials, repository, Clock.fixed(NOW, ZoneOffset.UTC),
                realAccount);
    }

    /** 真实账户资金快照（默认配置的单笔保证金是 100 USD）。 */
    private static RealAccountSource.Funds account(double equity, double available, int positions, long fetchedAtMs) {
        return new RealAccountSource.Funds(true, equity, available, positions, fetchedAtMs, null);
    }

    /** 供同包其它测试复用的「账户可读且资金充足」桩。 */
    static RealAccountSource availableAccount() {
        RealAccountSource source = mock(RealAccountSource.class);
        given(source.funds()).willReturn(account(1_000d, 500d, 0, NOW.toEpochMilli()));
        return source;
    }

    /** 把配置里的 key 当作环境变量装载进内存快照（等价于启动时 reload）。 */
    static OkxCredentials credentialsOf(OkxProperties.Strategy.Trading trading) {
        OkxCredentials credentials = new OkxCredentials();
        if (SecretMasker.configured(trading.apiKey()) && SecretMasker.configured(trading.secretKey())
                && SecretMasker.configured(trading.passphrase())) {
            credentials.apply(new OkxCredentials.Credential(trading.apiKey(), trading.secretKey(),
                    trading.passphrase(), null, OkxCredentials.SOURCE_ENV, null));
        }
        return credentials;
    }

    private static OkxProperties.Strategy.Trading trading(boolean enabled, String mode, String key, String secret,
            String pass, int maxPositions, double dailyCap) {
        return new OkxProperties.Strategy.Trading(enabled, mode, key, secret, pass, 0.0005d, 0.0002d, 2d,
                maxPositions, 100d, dailyCap, new OkxProperties.Strategy.Trading.DailyBatch(false, "0 30 8 * * *"));
    }

    static OkxProperties propsWithTrading(OkxProperties.Strategy.Trading trading) {
        OkxProperties d = OkxProperties.defaults();
        OkxProperties.Strategy s = d.strategy();
        return new OkxProperties(d.baseUrl(), d.connectTimeoutMs(), d.readTimeoutMs(), d.userAgent(), d.retry(),
                d.rateLimit(), d.backfill(), d.sync(), d.schedule(), d.apiLog(), d.db(),
                new OkxProperties.Strategy(s.enabled(), s.shadow(), s.health(), s.backtest(), trading));
    }
}
