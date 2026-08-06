package com.rltrader.tradingcore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * spec §2 검증 ①: 문자열→숫자·숫자→boolean coercion 금지 (malformed 응답 차단).
 *
 * <p>Boot 4.1 적응: 이 프로젝트의 jackson-databind는 3.x({@code tools.jackson.databind.*})다(브리프는
 * Jackson 2 {@code com.fasterxml.jackson.databind.*} 기준으로 작성됨). Jackson 3 변경점:
 * <ul>
 *   <li>java.time(OffsetDateTime 등) 지원이 databind 코어에 내장 — 브리프의
 *       {@code JavaTimeModule} 등록이 불필요(해당 클래스가 classpath에 없음). addModule 없이도
 *       bar_ts ISO datetime 파싱은 정상 동작(PredictClientTest로 검증).</li>
 *   <li>{@code DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE}은
 *       {@code DateTimeFeature}로 이전.</li>
 *   <li>coercion 설정은 {@code ObjectMapper.coercionConfigFor(...)}가 아니라 빌더의
 *       {@code withCoercionConfig(LogicalType, Consumer&lt;MutableCoercionConfig&gt;)}로 설정
 *       (Jackson 3에서 ObjectMapper는 build 후 불변).</li>
 * </ul>
 */
// proxyBeanMethods = false: strictObjectMapper()가 다른 @Bean 메서드를 호출하지 않는 단순
// 팩터리라 CGLIB 서브클래싱(full mode)이 불필요 — lite mode에서는 final class 허용.
@Configuration(proxyBeanMethods = false)
public final class JacksonConfig {

    private static final LogicalType[] STRING_COERCION_BANNED_TYPES =
            {LogicalType.Integer, LogicalType.Float, LogicalType.Boolean};

    public static ObjectMapper strictMapper() {
        JsonMapper.Builder builder = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // additive 계약 확장 허용
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        for (LogicalType type : STRING_COERCION_BANNED_TYPES) {
            builder.withCoercionConfig(type,
                    cfg -> cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
        }
        builder.withCoercionConfig(LogicalType.Boolean,
                cfg -> cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail));
        return builder.build();
    }

    @Bean
    public ObjectMapper strictObjectMapper() {
        return strictMapper();
    }
}
