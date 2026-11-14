package com.okexalpha.service.strategy.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.strategy.engine.ShadowMath;

/**
 * 共享账户预算：把「真实账户权益 + 全部影子持仓已用保证金」放到同一份快照里。
 *
 * <p>为什么需要它：策略层闸门（{@link com.okexalpha.strategy.engine.ShadowMath#allowsLayer}）只看
 * <b>单个币</b>的 {@code accountCapitalUsd}，4 个币各自"够用"加起来却远超账户；实盘门禁（{@link TradingGate}）
 * 又只看真实可用资金，和策略层的数字对不上。这里做成唯一出口：影子开仓/加仓与实盘门禁都问同一个问题——
 * 「再占这么多保证金，还在权益的 95% 以内吗」。</p>
 *
 * <p>权益来源两级：真实账户（{@link RealAccountSource}，失败时不静默放行）→ 策略配置的每币资金之和
 * （{@code okx_strategy.capital_per_coin_usd}）。两级都拿不到时权益为 0，闸门一律拒绝（fail-closed）。</p>
 */
@Component
public class AccountBudget {

    private static final Logger log = LoggerFactory.getLogger(AccountBudget.class);

    /** 权益来源：真实账户 / 策略配置 / 不可用。 */
    public static final String SOURCE_REAL = "real-account";
    public static final String SOURCE_CONFIGURED = "configured-capital";
    public static final String SOURCE_UNAVAILABLE = "unavailable";

    /**
     * 账户预算快照。
     *
     * @param fromRealAccount 权益是否来自真实账户
     * @param equityUsd       账户权益（USD）；0 表示拿不到，任何新增保证金都应被拒绝
     * @param usedMarginUsd   已用保证金（全部开放影子持仓）
     * @param source          来源标记，便于在日志/健康度里核对口径
     */
    public record Budget(boolean fromRealAccount, double equityUsd, double usedMarginUsd, String source) {

        /** 本次最多还能占用的保证金（权益 × 95%）。 */
        public double gateUsd() {
            return equityUsd * ShadowMath.LAYER_HEADROOM;
        }

        /** 剩余可占用保证金（可能为负，表示已经超配）。 */
        public double headroomUsd() {
            return gateUsd() - usedMarginUsd;
        }
    }

    private final RealAccountSource realAccount;
    private final ShadowRepository repository;

    public AccountBudget(RealAccountSource realAccount, ShadowRepository repository) {
        this.realAccount = realAccount;
        this.repository = repository;
    }

    /** 全账户预算：权益优先取真实账户，读不到时退回「活跃策略的每币资金之和」。 */
    public Budget portfolio() {
        double used = safeMargin(() -> repository.totalOpenMargin());
        RealAccountSource.Funds funds = null;
        try {
            funds = realAccount == null ? null : realAccount.funds();
        } catch (RuntimeException ex) {
            log.warn("读取真实账户资金失败，退回配置口径：{}", ex.toString());
        }
        if (funds != null && funds.available() && funds.equityUsd() > 0d) {
            return new Budget(true, funds.equityUsd(), used, SOURCE_REAL);
        }
        double configured = safeMargin(() -> repository.configuredCapitalSum());
        return new Budget(false, configured, used, configured > 0d ? SOURCE_CONFIGURED : SOURCE_UNAVAILABLE);
    }

    /** 再占用 {@code addMarginUsd} 保证金是否还在共享账户的 95% 以内（fail-closed）。 */
    public boolean allows(double addMarginUsd) {
        Budget budget = portfolio();
        boolean ok = ShadowMath.allowsLayer(budget.equityUsd(), 0d, budget.usedMarginUsd(), addMarginUsd);
        if (!ok) {
            log.info("共享账户闸门拒绝新增保证金 {}U：权益 {}U（{}），已用 {}U，上限 {}U", round2(addMarginUsd),
                    round2(budget.equityUsd()), budget.source(), round2(budget.usedMarginUsd()),
                    round2(budget.gateUsd()));
        }
        return ok;
    }

    private static double safeMargin(java.util.function.Supplier<Double> supplier) {
        try {
            Double value = supplier.get();
            return value == null || !Double.isFinite(value) || value < 0d ? 0d : value;
        } catch (RuntimeException ex) {
            // 表缺失/连接失败都按 0 处理：宁可不开新仓，也不能凭空认为还有额度
            return 0d;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
