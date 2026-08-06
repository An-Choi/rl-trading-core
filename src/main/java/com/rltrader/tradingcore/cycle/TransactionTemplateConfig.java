package com.rltrader.tradingcore.cycle;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DecisionCycleService의 단일 트랜잭션(decisions+executions+snapshot) 실행에 사용. */
@Configuration(proxyBeanMethods = false)
public class TransactionTemplateConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
