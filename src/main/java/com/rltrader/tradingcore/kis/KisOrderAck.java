package com.rltrader.tradingcore.kis;

/**
 * 주문 제출 성공 응답 — KIS가 부여한 주문번호 + 정정취소 시 필요한 원주문 지점(모의투자는
 * 항상 공백이 유효하지만 실전은 값이 있을 수 있음 — Fix 1, KIS 정정취소 공식 샘플).
 * krxFwdgOrdOrgno는 응답에 없거나 공백이면 null(defensive parse — KisBrokerClient.ackFrom 참고).
 */
public record KisOrderAck(String kisOrderNo, String krxFwdgOrdOrgno) {

    /** 편의 생성자 — 조직번호를 모르는(또는 신경 쓰지 않는) 호출부/테스트용, null로 위임. */
    public KisOrderAck(String kisOrderNo) {
        this(kisOrderNo, null);
    }
}
