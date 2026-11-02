package com.okexalpha.service.storage;

import java.util.List;

/**
 * GET /api/v1/maintenance/storage 的返回结构：库体积 + 表体积 + TimescaleDB 分块/压缩状况。
 *
 * <p>压缩后的数据实际落在 chunk 的 TOAST 里，所以只看 after_compression_table_bytes 会误判；
 * 这里统一用 after_compression_total_bytes（table+index+toast）与 hypertable_size。
 */
public record StorageReport(
        String database,
        long databaseBytes,
        TimescaleInfo timescale,
        List<TableInfo> tables) {

    public record TimescaleInfo(
            boolean installed,
            String version,
            List<HypertableInfo> hypertables,
            List<CompressionPolicy> policies) {
    }

    /** 单个 hypertable：分块数、压缩分块数、压缩前后体积与压缩比。 */
    public record HypertableInfo(
            String tableName,
            long totalBytes,
            long chunkCount,
            long compressedChunkCount,
            Long beforeCompressionBytes,
            Long afterCompressionBytes,
            Double compressionRatio,
            String segmentBy,
            String orderBy,
            Long compressAfterMs) {
    }

    public record CompressionPolicy(
            long jobId,
            String procName,
            String hypertableName,
            String scheduleInterval,
            String config) {
    }

    /** 普通表 totalBytes 是 pg_total_relation_size；hypertable 用 hypertable_size（含所有 chunk）。 */
    public record TableInfo(
            String tableName,
            boolean hypertable,
            long tableBytes,
            long indexBytes,
            long totalBytes,
            long estimatedRows,
            Double bytesPerRow) {
    }
}
