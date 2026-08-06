package com.rltrader.tradingcore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
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

    public static JsonMapper strictMapper() {
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

    // Boot의 JacksonAutoConfiguration#jacksonJsonMapper()는 @Bean @Primary @ConditionalOnMissingBean
    // (반환타입 JsonMapper 기준)으로 lenient 기본 ObjectMapper를 등록한다. 이 빈의 선언 반환타입을
    // ObjectMapper로 두면 @ConditionalOnMissingBean이 타입 불일치로 충돌을 인식 못해 auto-configured
    // 빈이 함께 등록되고, @Primary 없이는 그쪽이 이겨 실제 주입은 lenient mapper가 됨(리뷰에서
    // 리플렉션으로 실증). 반대로 이 빈에도 @Primary를 붙이면 @Primary 빈이 2개가 되어
    // NoUniqueBeanDefinitionException.
    //
    // 해결: 선언 반환타입을 auto-config 메서드와 동일한 JsonMapper로 맞춘다 — 그러면
    // @ConditionalOnMissingBean(JsonMapper 기준)이 이 빈을 인식해 자기 자신을 등록하지 않으므로
    // 후보가 하나(strictObjectMapper)만 남는다. @Primary는 불필요(경쟁자가 없음)하지만, 향후 다른
    // 모듈이 별도 ObjectMapper/JsonMapper 빈을 추가해도 이 빈이 우선하도록 방어적으로 유지한다.
    @Bean
    @Primary
    public JsonMapper strictObjectMapper() {
        return strictMapper();
    }
}
