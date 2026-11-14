package com.okexalpha.service.strategy.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.repository.ShadowRepository;

/** 共享账户预算：真实账户优先、读不到退回配置口径、两级都缺时 fail-closed。 */
class AccountBudgetTest {

    private ShadowRepository repository;
    private RealAccountSource realAccount;
    private AccountBudget budget;

    @BeforeEach
    void setUp() {
        repository = mock(ShadowRepository.class);
        realAccount = mock(RealAccountSource.class);
        budget = new AccountBudget(realAccount, repository);
    }

    @Test
    @DisplayName("真实账户可用 → 用真实权益，并且记下来源")
    void prefersRealAccountEquity() {
        given(realAccount.funds()).willReturn(new RealAccountSource.Funds(true, 942.34d, 126.22d, 26, 1L, "ok"));
        given(repository.totalOpenMargin()).willReturn(400.64d);

        AccountBudget.Budget b = budget.portfolio();

        assertThat(b.fromRealAccount()).isTrue();
        assertThat(b.equityUsd()).isEqualTo(942.34d);
        assertThat(b.usedMarginUsd()).isEqualTo(400.64d);
        assertThat(b.source()).isEqualTo(AccountBudget.SOURCE_REAL);
        assertThat(b.gateUsd()).isCloseTo(942.34d * 0.95d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("真实账户不可用 → 退回策略配置的每币资金之和")
    void fallsBackToConfiguredCapital() {
        given(realAccount.funds()).willReturn(RealAccountSource.Funds.unavailable("no credentials"));
        given(repository.configuredCapitalSum()).willReturn(800d);
        given(repository.totalOpenMargin()).willReturn(0d);

        AccountBudget.Budget b = budget.portfolio();

        assertThat(b.fromRealAccount()).isFalse();
        assertThat(b.equityUsd()).isEqualTo(800d);
        assertThat(b.source()).isEqualTo(AccountBudget.SOURCE_CONFIGURED);
    }

    @Test
    @DisplayName("两级都拿不到 → 权益 0、来源 unavailable、任何新增保证金都被拒（fail-closed）")
    void failsClosedWhenNoEquityAvailable() {
        given(realAccount.funds()).willReturn(RealAccountSource.Funds.unavailable("boom"));
        given(repository.configuredCapitalSum()).willReturn(0d);

        AccountBudget.Budget b = budget.portfolio();

        assertThat(b.equityUsd()).isZero();
        assertThat(b.source()).isEqualTo(AccountBudget.SOURCE_UNAVAILABLE);
        assertThat(budget.allows(1d)).isFalse();
    }

    @Test
    @DisplayName("仓库读库异常按 0 处理，不会因此凭空多出额度")
    void repositoryFailureDoesNotGrantHeadroom() {
        given(realAccount.funds()).willReturn(RealAccountSource.Funds.unavailable("n/a"));
        given(repository.configuredCapitalSum()).willThrow(new IllegalStateException("db down"));
        given(repository.totalOpenMargin()).willThrow(new IllegalStateException("db down"));

        assertThat(budget.portfolio().equityUsd()).isZero();
        assertThat(budget.allows(10d)).isFalse();
    }

    @Test
    @DisplayName("跨币共享：已用 860U 时，剩 100U 的层开不出来（权益 1000U → 上限 950U）")
    void sharedBudgetBlocksWhenPortfolioIsFull() {
        given(realAccount.funds()).willReturn(new RealAccountSource.Funds(true, 1_000d, 900d, 0, 1L, "ok"));
        given(repository.totalOpenMargin()).willReturn(860d);

        assertThat(budget.allows(100d)).isFalse();   // 860 + 100 = 960 > 950
        assertThat(budget.allows(90d)).isTrue();     // 860 + 90 = 950 = 上限
    }

    @Test
    @DisplayName("单币各自够用不够：两个币各 100U 在 150U 权益下只能开一个")
    void coinLevelChecksDoNotAddUp() {
        given(realAccount.funds()).willReturn(new RealAccountSource.Funds(true, 150d, 150d, 0, 1L, "ok"));
        given(repository.totalOpenMargin()).willReturn(0d);
        assertThat(budget.allows(100d)).isTrue();

        // 第一个币占住 100U 后，第二个币的 100U 超出 142.5U 上限
        given(repository.totalOpenMargin()).willReturn(100d);
        assertThat(budget.allows(100d)).isFalse();
    }
}
