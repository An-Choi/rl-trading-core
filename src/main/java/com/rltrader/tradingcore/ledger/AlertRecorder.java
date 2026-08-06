package com.rltrader.tradingcore.ledger;

import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * spec §5 알림 채널 계약: 구조화 로그가 필수, alerts INSERT는 best-effort.
 *
 * <p>Boot 4.1 적응: 브리프는 Jackson 2({@code com.fasterxml.jackson.databind.ObjectMapper})
 * 기준으로 작성됐지만 이 프로젝트의 jackson-databind는 3.x({@code tools.jackson.databind.*})다
 * (JacksonConfig 참고). 생성자 파라미터 타입을 {@code tools.jackson.databind.ObjectMapper}로 두면
 * JacksonConfig의 {@code @Primary JsonMapper} 빈(JsonMapper extends ObjectMapper)이 그대로
 * 주입된다. 또한 Jackson 3의 {@code writeValueAsString}은 checked
 * {@code JsonProcessingException}이 아니라 unchecked {@code JacksonException}을 던지므로
 * catch 대상도 그에 맞춘다. {@code JacksonException}은 이미 {@code RuntimeException}의
 * 서브타입이라 별도 멀티캐치 없이 {@code RuntimeException} 하나로 직렬화 실패·DB 실패를
 * 모두 포괄한다.
 */
@Component
public class AlertRecorder {

    private static final Logger log = LoggerFactory.getLogger(AlertRecorder.class);
    private static final int CODE_MAX_LENGTH = 32;   // alerts.code VARCHAR(32) (V1__init.sql)

    private final AlertRepository alerts;
    private final ObjectMapper mapper;

    public AlertRecorder(AlertRepository alerts, ObjectMapper strictMapper) {
        this.alerts = alerts;
        this.mapper = strictMapper;
    }

    public void record(String code, ZonedDateTime barTs, Map<String, Object> detail) {
        log.error("[ALERT] code={} barTs={} detail={}", code, barTs, detail);
        try {
            AlertEntity e = new AlertEntity();
            // 컬럼 길이를 넘는 code는 best-effort INSERT 전체를 실패시켜 계약 위반 상황에서
            // 오히려 행을 잃는다 — truncate로 저장은 보장한다.
            e.setCode(code.length() > CODE_MAX_LENGTH ? code.substring(0, CODE_MAX_LENGTH) : code);
            e.setBarTs(barTs != null ? barTs.toOffsetDateTime() : null);
            e.setDetail(mapper.writeValueAsString(detail));
            alerts.save(e);
        } catch (RuntimeException dbFailure) {
            // DB 불능 시 같은 DB에 alert를 남길 수 없다 — 로그가 유일한 채널 (spec §5)
            log.error("[ALERT] alerts INSERT failed (logged only): {}", dbFailure.toString());
        }
    }
}
