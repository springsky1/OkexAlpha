package com.okexalpha.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 调度（阶段 C / P2 影子引擎、P3 健康度检查依赖）。
 *
 * <p>`okx.schedule.enabled=false` 可整体关掉定时任务（本地跑测试/临时实例时可关）；
 * 关掉后影子引擎与健康度检查就只能靠对应的 POST 接口手动触发。</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "okx.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
