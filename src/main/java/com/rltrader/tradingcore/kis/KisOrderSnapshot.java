package com.rltrader.tradingcore.kis;

import java.math.BigDecimal;

/**
 * KIS 조회 응답(일별주문체결·개별주문조회)의 정규화 스냅샷.
 *
 * <p>{@code brokerTerminal}: 브로커 관점에서 더 이상 상태 변화가 없는 종결 상태
 * (잔여수량 0 이거나 취소 확정) — {@code (rmn_qty == 0 || cncl_yn == "Y")}.
 * {@code cancelled}: 취소 확정 여부 — {@code (cncl_yn == "Y")}.
 * {@code krxFwdgOrdOrgno}: 정정취소 시 필요한 원주문 지점 — Fix A(2차 리뷰), 방어적 파싱
 * (필드 부재/공백 → null, {@link KisBrokerClient#toSnapshot} 참고). 복구·채택 경로(SUBMIT_UNKNOWN
 * 해소, boot orphan 해소)가 제출 ack 없이 조회만으로 주문을 알게 될 때 이 값이 유일한 출처다.
 */
public record KisOrderSnapshot(
        String kisOrderNo,
        long orderQty,
        long cumulativeFilledQty,
        BigDecimal cumulativeAvgPrice,
        boolean brokerTerminal,
        boolean cancelled,
        String krxFwdgOrdOrgno) {

    /** 편의 생성자 — 조직번호를 신경 쓰지 않는 기존 테스트 호출부용, null로 위임. */
    public KisOrderSnapshot(String kisOrderNo, long orderQty, long cumulativeFilledQty,
            BigDecimal cumulativeAvgPrice, boolean brokerTerminal, boolean cancelled) {
        this(kisOrderNo, orderQty, cumulativeFilledQty, cumulativeAvgPrice, brokerTerminal, cancelled, null);
    }
}
