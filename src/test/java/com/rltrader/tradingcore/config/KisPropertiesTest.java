package com.rltrader.tradingcore.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KisPropertiesTest {

    @Test
    void binds_kis_and_order_properties() {
        new ApplicationContextRunner()
            .withUserConfiguration(Holder.class)
            .withPropertyValues(
                "kis.base-url=https://openapivts.koreainvestment.com:29443",
                "kis.app-key=k", "kis.app-secret=s",
                "kis.account-no=12345678", "kis.account-product-code=01",
                "kis.connect-timeout-ms=3000", "kis.read-timeout-ms=10000",
                "order.fill-poll-interval-ms=2000", "order.fill-timeout-ms=60000",
                "order.closing-auction-deadline=15:35",
                "order.submit-retry-max=1", "order.reject-retry-max=3",
                "order.submit-unknown-max-lookups=5", "order.recon-interval-ms=30000")
            .run(ctx -> {
                assertThat(ctx.getBean(KisProperties.class).accountNo()).isEqualTo("12345678");
                assertThat(ctx.getBean(OrderProperties.class).fillTimeoutMs()).isEqualTo(60000L);
            });
    }

    @EnableConfigurationProperties({KisProperties.class, OrderProperties.class})
    static class Holder {}
}
