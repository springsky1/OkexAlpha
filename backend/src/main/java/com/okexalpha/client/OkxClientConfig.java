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

    @Bean
    public OkxRateLimiter okxRateLimiter(OkxProperties properties) {
        Map<String, Integer> limits = Map.of(
                GROUP_INSTRUMENTS, Math.max(1, properties.rateLimit().instrumentsPer2s()),
                GROUP_CANDLES, Math.max(1, properties.rateLimit().candlesPer2s()),
                GROUP_FUNDING, Math.max(1, properties.rateLimit().fundingPer2s()),
                GROUP_RUBIK, Math.max(1, properties.rateLimit().rubikPer2s()));
        return new OkxRateLimiter(Clock.systemUTC(), 2_000L, limits);
    }
}
