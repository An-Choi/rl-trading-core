package com.rltrader.tradingcore.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rltrader.tradingcore.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 회귀 테스트: Boot의 JacksonAutoConfiguration이 @Primary lenient ObjectMapper(jacksonJsonMapper)를
 * 자동 등록하기 때문에, JacksonConfig.strictObjectMapper()에 @Primary가 없으면 실제 Spring 컨텍스트에서
 * PredictClient 등에 주입되는 ObjectMapper는 strict mapper가 아니라 lenient 기본 mapper가 된다
 * (PredictClientTest는 new PredictClient(props, JacksonConfig.strictMapper())로 수동 생성해
 * mapper를 직접 넘기므로 이 배선 버그를 잡지 못함 — 반드시 @Autowired 컨텍스트 경유로 검증해야 함).
 */
class JacksonConfigContextTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

    private record NumberHolder(double v) {}

    @Test
    void autowiredMapperRejectsStringToNumberCoercion() {
        assertThatThrownBy(() -> mapper.readValue("{\"v\": \"1.5\"}", NumberHolder.class))
                .isInstanceOf(RuntimeException.class);
    }
}
