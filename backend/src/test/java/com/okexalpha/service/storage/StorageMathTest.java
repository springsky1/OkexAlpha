package com.okexalpha.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** StorageService 的纯计算部分：policy 配置解析、体积换算、可空数值兜底。 */
class StorageMathTest {

    @Test
    void parsesCompressAfterFromPolicyConfig() {
        assertThat(StorageService.compressAfterOf("{\"hypertable_id\": 1, \"compress_after\": 2592000000}"))
                .isEqualTo(2592000000L);
        assertThat(StorageService.compressAfterOf("{\"compress_after\": 604800000}")).isEqualTo(604800000L);
        assertThat(StorageService.compressAfterOf("{ \"compress_after\" :  86400000 }")).isEqualTo(86400000L);
    }

    @Test
    void returnsNullForMissingOrIntervalCompressAfter() {
        assertThat(StorageService.compressAfterOf(null)).isNull();
        assertThat(StorageService.compressAfterOf("{}")).isNull();
        assertThat(StorageService.compressAfterOf("{\"hypertable_id\": 1}")).isNull();
        // interval 形式不是毫秒，宁可显示未知也不要误报成 7 毫秒
        assertThat(StorageService.compressAfterOf("{\"compress_after\": \"7 days\"}")).isNull();
        assertThat(StorageService.compressAfterOf("not json at all")).isNull();
    }

    @Test
    void nullableLongFallsBackToZero() {
        assertThat(StorageService.nullableLong(null)).isZero();
        assertThat(StorageService.nullableLong(12L)).isEqualTo(12L);
        assertThat(StorageService.nullableLong(BigDecimal.valueOf(34))).isEqualTo(34L);
        assertThat(StorageService.nullableLong("99")).isZero();
    }

    @Test
    void round2KeepsTwoDecimals() {
        assertThat(StorageService.round2(5.8333333)).isEqualTo(5.83);
        assertThat(StorageService.round2(60.625)).isEqualTo(60.63);
        assertThat(StorageService.round2(1.0)).isEqualTo(1.0);
    }
}
