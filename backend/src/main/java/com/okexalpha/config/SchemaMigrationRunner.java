package com.okexalpha.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.okexalpha.repository.SchemaMigrationRepository;

/**
 * 增量结构迁移：按文件名顺序执行 classpath:db/migrations/*.sql，并把 id + SHA-256 记进
 * okx_schema_migration。已应用的迁移不会重复执行；同一个 id 但文件内容变了只告警不重跑
 * （结构变更请新增迁移文件，而不是改历史文件）。
 *
 * <p>整份脚本一次性交给 PostgreSQL 驱动执行，而不是用 ScriptUtils 按分号切分——迁移里大量使用
 * {@code DO $$ ... $$} 块，按分号切会切断 dollar-quoted 函数体（报 unterminated dollar quote）。
 * 迁移与账本写入在同一个事务里（PG 的 DDL 可事务化），失败即整体回滚并让启动失败，
 * 避免"半个迁移"的中间态。
 */
@Component
public class SchemaMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    public static final String LOCATION_PATTERN = "classpath:db/migrations/*.sql";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaMigrationRepository repository;
    private final TransactionTemplate transactionTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, SchemaMigrationRepository repository,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 按顺序应用尚未应用的迁移，返回本次新应用的 id 列表。 */
    public List<String> run() {
        List<Resource> resources = loadResources();
        if (resources.isEmpty()) {
            log.info("no db migrations found at {}", LOCATION_PATTERN);
            return List.of();
        }
        repository.ensureLedger();
        Map<String, String> applied = repository.applied();
        List<String> appliedNow = new ArrayList<>();
        for (Resource resource : resources) {
            String id = idOf(resource);
            String checksum = checksum(resource);
            String previous = applied.get(id);
            if (previous != null) {
                if (!previous.equals(checksum)) {
                    log.warn("migration {} already applied with a different checksum (db={}, jar={}); "
                            + "add a new migration file instead of editing history", id, previous, checksum);
                }
                continue;
            }
            applyAndRecord(resource, id, checksum);
            appliedNow.add(id);
        }
        if (appliedNow.isEmpty()) {
            log.info("db migrations up to date: {} applied script(s)", applied.size());
        } else {
            log.info("db migrations applied: {} (already applied: {})", appliedNow, applied.size());
        }
        return appliedNow;
    }

    private void applyAndRecord(Resource resource, String id, String checksum) {
        String script = text(resource);
        long startedAt = System.nanoTime();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute(script);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            repository.record(id, checksum, durationMs);
        });
        log.info("migration {} applied", id);
    }

    private static String text(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read migration " + resource.getDescription(), ex);
        }
    }

    private static List<Resource> loadResources() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN);
            List<Resource> resources = new ArrayList<>(Arrays.asList(found));
            resources.sort(Comparator.comparing(SchemaMigrationRunner::idOf));
            return resources;
        } catch (IOException ex) {
            throw new IllegalStateException("cannot list db migrations: " + LOCATION_PATTERN, ex);
        }
    }

    /** 001_kline_and_funding_types.sql -> 001_kline_and_funding_types */
    static String idOf(Resource resource) {
        String name = resource.getFilename();
        if (name == null) {
            return String.valueOf(resource.getDescription());
        }
        return name.endsWith(".sql") ? name.substring(0, name.length() - 4) : name;
    }

    static String checksum(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("cannot checksum migration " + resource.getDescription(), ex);
        }
    }
}
