package com.okexalpha.client;

import java.time.Clock;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.okexalpha.config.OkxProperties;

@Configuration
public class OkxClientConfig {

    public static final String GROUP_INSTRUMENTS = "instruments";
    public static final String GROUP_CANDLES = "candles";
    public static final String GROUP_FUNDING = "funding";
    public static final String GROUP_RUBIK = "rubik";
    /** 私有交易接口（下单/平仓/杠杆/持仓）。官方下限较宽（下单 60 次/2s），这里保守取 10。 */
    public static final String GROUP_TRADE = "trade";
    /** 全局窗口：所有 REST 调用共享的 IP 级总配额。 */
    public static final String GROUP_GLOBAL = "global";

    @Bean
    public OkxRateLimiter okxRateLimiter(OkxProperties properties) {
        Map<String, Integer> limits = Map.of(
                GROUP_INSTRUMENTS, Math.max(1, properties.rateLimit().instrumentsPer2s()),
                GROUP_CANDLES, Math.max(1, properties.rateLimit().candlesPer2s()),
                GROUP_FUNDING, Math.max(1, properties.rateLimit().fundingPer2s()),
                GROUP_RUBIK, Math.max(1, properties.rateLimit().rubikPer2s()),
                GROUP_TRADE, 10,
                GROUP_GLOBAL, Math.max(1, properties.rateLimit().globalPer2s()));
        return new OkxRateLimiter(Clock.systemUTC(), 2_000L, limits);
    }
}
