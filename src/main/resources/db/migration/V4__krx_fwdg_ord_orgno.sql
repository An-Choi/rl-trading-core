-- fix review 1 (P1) — KIS 정정취소 공식 샘플 정합: 원주문 제출 ack의 KRX_FWDG_ORD_ORGNO를
-- 보존해 취소 요청 시 그대로 전달한다(부재 시에만 공백). nullable — 모의투자 응답은 공백/부재일
-- 수 있고, 기존 행은 소급 채울 수 없다.
ALTER TABLE orders ADD COLUMN krx_fwdg_ord_orgno VARCHAR(16);
