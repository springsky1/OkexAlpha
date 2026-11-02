package com.okexalpha.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 启动时的结构自检，顺序：
 * <ol>
 *   <li>okx.db.auto-init=true 时执行 classpath:db/schema.sql（幂等建表）</li>
 *   <li>okx.db.migrate=true 时应用 classpath:db/migrations/*.sql（TimescaleDB 分块/压缩等增量改造）</li>
 *   <li>校验 24 张表都存在，缺表直接启动失败</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    public static final List<String> REQUIRED_TABLES = List.of(
            "okx_instrument", "okx_coin", "okx_kline", "okx_funding_rate",
            "okx_long_short_ratio", "okx_open_interest_volume", "okx_taker_volume", "okx_open_interest",
            "okx_backfill_job", "okx_backfill_task", "okx_sync_cursor", "okx_api_call_log",
            "okx_schema_migration",
            // 策略相关（阶段 C）
            "okx_min_order", "okx_strategy", "okx_backtest_run", "okx_backtest_result", "okx_backtest_trade",
            "okx_backtest_equity", "okx_shadow_order", "okx_shadow_position", "okx_health_check",
            "okx_portfolio_snapshot", "okx_credential_vault");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final OkxProperties properties;
    private final SchemaMigrationRunner migrationRunner;

    public SchemaInitializer(DataSource dataSource, JdbcTemplate jdbcTemplate, OkxProperties properties,
            SchemaMigrationRunner migrationRunner) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.migrationRunner = migrationRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.db().autoInit()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql"));
            populator.setContinueOnError(false);
            populator.execute(dataSource);
            log.info("okx schema applied from classpath:db/schema.sql");
        }
        if (properties.db().migrate()) {
            migrationRunner.run();
        }
        List<String> missing = REQUIRED_TABLES.stream()
                .filter(table -> jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, "public." + table) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing okx tables: " + missing + " (run scripts/init-db.ps1)");
        }
        log.info("okx schema verified: {} tables", REQUIRED_TABLES.size());
    }
}
