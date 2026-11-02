package com.okexalpha.service.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.okexalpha.repository.BacktestRepository;

/** 回测重启恢复：纯内存计算无法续跑，把上一个进程遗留的 PENDING/RUNNING 一律标 FAILED。 */
@Component
@Order(120)
public class BacktestRecovery implements ApplicationRunner {

    static final String INTERRUPTED_ERROR = "interrupted by restart";

    private static final Logger log = LoggerFactory.getLogger(BacktestRecovery.class);

    private final BacktestRepository repository;

    public BacktestRecovery(BacktestRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int interrupted = repository.markInterrupted(INTERRUPTED_ERROR);
        if (interrupted > 0) {
            log.warn("marked {} interrupted backtest run(s) as FAILED ({})", interrupted, INTERRUPTED_ERROR);
        }
    }
}
