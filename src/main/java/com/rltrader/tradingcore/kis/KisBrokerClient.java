package com.rltrader.tradingcore.kis;

import com.rltrader.tradingcore.config.KisProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * KIS(한국투자증권) OpenAPI 브로커 클라이언트 — BrokerPort 구현.
 *
 * <p>구성은 KisTokenManager와 동일한 RestClient 패턴(HTTP/1.1 강제 + JDK HttpClient 타임아웃).
 * TR ID는 모의투자 기준 초안이며 KIS 문서 재확인 필요 — {@link TrId} 참고.
 */
@Component
public class KisBrokerClient implements BrokerPort {

    /** 모의투자 TR ID 초안 — KIS 문서 재확인 필요 — 스모크 실검증. */
    private static final class TrId {
        // 현금주문(order-cash) 공식 샘플 기준 — 모의투자 VTTC0012U(매수)/VTTC0011U(매도)
        // (실전 TTTC0012U/TTTC0011U). Fix B(2차 리뷰) — 이전 VTTC0802U/VTTC0801U는 구세대 TR ID였다.
        static final String BUY = "VTTC0012U";
        static final String SELL = "VTTC0011U";
        // 정정취소(order-rvsecncl) 공식 샘플 기준 — 모의투자 VTTC0013U(실전 TTTC0013U). KIS 문서
        // 재확인 필요(Fix 1 — 이전 VTTC0803U는 정정취소 엔드포인트의 공식 TR ID가 아니었다).
        static final String CANCEL = "VTTC0013U";
        static final String DAILY_CCLD = "VTTC8001R";
        static final String BALANCE = "VTTC8434R";
    }

    private static final String ORDER_SUBMIT_PATH = "/uapi/domestic-stock/v1/trading/order-cash";
    // 정정취소주문(order-rvsecncl) 엔드포인트 — KIS 문서 재확인 필요. 취소 본문의
    // RVSE_CNCL_DVSN_CD/KRX_FWDG_ORD_ORGNO/QTY_ALL_ORD_YN 필드값과 ORD_DVSN="00"(시장가 "01"을
    // 그대로 재사용하지 않음 — 취소는 가격 구분이 무의미하므로 KIS 문서상 통용되는 플레이스홀더
    // 값으로 "00"을 채택했다)도 전부 미검증 초안이다.
    private static final String ORDER_CANCEL_PATH = "/uapi/domestic-stock/v1/trading/order-rvsecncl";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final KisProperties props;
    private final KisTokenManager tokenManager;
    private final JsonMapper mapper;
    private final RestClient rest;

    public KisBrokerClient(KisProperties props, KisTokenManager tokenManager, JsonMapper mapper) {
        this.props = props;
        this.tokenManager = tokenManager;
        this.mapper = mapper;
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs())).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public KisOrderAck submitMarketOrder(String symbol, OrderSide side, long qty) {
        String trId = (side == OrderSide.BUY) ? TrId.BUY : TrId.SELL;
        Map<String, Object> body = orderBody(symbol, qty);
        return submitOrder(ORDER_SUBMIT_PATH, trId, body);
    }

    @Override
    public void cancelOrder(String kisOrderNo, long remainingQty, String krxFwdgOrdOrgno) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("CANO", props.accountNo());
        body.put("ACNT_PRDT_CD", props.accountProductCode());
        // 원주문 제출 ack가 반환한 값을 그대로 전달 — 부재(null/공백)면 공식 샘플대로 공백 전송.
        body.put("KRX_FWDG_ORD_ORGNO", krxFwdgOrdOrgno == null ? "" : krxFwdgOrdOrgno);
        body.put("ORGN_ODNO", kisOrderNo);
        body.put("ORD_DVSN", "00"); // 취소는 가격 구분 무의미 — 플레이스홀더(재확인 필요)
        body.put("RVSE_CNCL_DVSN_CD", "02"); // 02 = 취소
        body.put("EXCG_ID_DVSN_CD", "KRX"); // 공식 정정취소 샘플 필수 필드(Fix 1)
        body.put("ORD_QTY", String.valueOf(remainingQty));
        body.put("ORD_UNPR", "0");
        body.put("QTY_ALL_ORD_YN", "Y");
        submitOrder(ORDER_CANCEL_PATH, TrId.CANCEL, body);
    }

    private Map<String, Object> orderBody(String symbol, long qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("CANO", props.accountNo());
        body.put("ACNT_PRDT_CD", props.accountProductCode());
        body.put("PDNO", symbol);
        body.put("ORD_DVSN", "01"); // 01 = 시장가
        body.put("ORD_QTY", String.valueOf(qty));
        body.put("ORD_UNPR", "0");
        body.put("EXCG_ID_DVSN_CD", "KRX"); // 공식 현금주문 샘플 필수 필드(Fix B, 2차 리뷰)
        return body;
    }

    /**
     * 주문 제출/취소 공통 경로. requestSent는 주문 POST 자체의 전송 실패에서만 판정한다 —
     * hashkey 발급이나 토큰 준비 단계 실패는 주문이 송신된 적이 없으므로 무조건
     * requestSent=false (SUBMIT_UNKNOWN 오분류 방지). 반대로 주문 POST 자체가 응답을
     * 받았다면(정상이든 non-2xx든) 요청은 실제로 송신된 것이므로 requestSent=true.
     */
    private KisOrderAck submitOrder(String path, String trId, Map<String, Object> body) {
        String token;
        String hashKey;
        try {
            token = tokenManager.accessToken();
            hashKey = hashKey(body);
        } catch (KisTransportException e) {
            throw new KisTransportException(e.getMessage(), false, e.getCause());
        } catch (ResourceAccessException e) {
            throw new KisTransportException("hashkey issue failed", false, e);
        } catch (RestClientResponseException e) {
            throw new KisTransportException("hashkey request rejected: " + e.getStatusCode(), false, e);
        }

        JsonNode response;
        try {
            String raw = rest.post().uri(path)
                    .headers(h -> orderHeaders(h, token, trId, hashKey))
                    .body(body)
                    .retrieve().body(String.class);
            response = parse(raw);
        } catch (ResourceAccessException e) {
            throw new KisTransportException("order submit failed", !KisTokenManager.isConnectFailure(e), e);
        } catch (RestClientResponseException e) {
            // 응답을 받았다는 것은 요청이 실제로 송신되었다는 뜻 — requestSent=true.
            throw new KisTransportException("order submit rejected: " + e.getStatusCode(), true, e);
        }
        return ackFrom(response);
    }

    private KisOrderAck ackFrom(JsonNode response) {
        String rtCd = textOrNull(response.path("rt_cd"));
        if (!"0".equals(rtCd)) {
            throw new KisOrderRejectedException(rtCd, textOrNull(response.path("msg1")));
        }
        JsonNode output = response.path("output");
        String odno = strictText(output.path("ODNO"), "output.ODNO");
        // 방어적 파싱 — 필드 부재/공백이면 null(취소 시 공백 전송으로 폴백). ODNO와 달리
        // strict 검증하지 않는다: 이 필드는 응답에 없을 수 있는 것이 정상 케이스다.
        String krxFwdgOrdOrgno = orNullIfBlank(textOrNull(output.path("KRX_FWDG_ORD_ORGNO")));
        return new KisOrderAck(odno, krxFwdgOrdOrgno);
    }

    private static String orNullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @Override
    public List<KisOrderSnapshot> dailyOrders(LocalDate tradingDate) {
        List<KisOrderSnapshot> result = new ArrayList<>();
        String ctxFk = "";
        String ctxNk = "";
        boolean more = true;
        String trCont = "";
        while (more) {
            String fk = ctxFk;
            String nk = ctxNk;
            String cont = trCont;
            var page = queryWithRetry(() -> {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("CANO", props.accountNo());
                params.put("ACNT_PRDT_CD", props.accountProductCode());
                params.put("INQR_STRT_DT", tradingDate.format(DATE_FMT));
                params.put("INQR_END_DT", tradingDate.format(DATE_FMT));
                params.put("SLL_BUY_DVSN_CD", "00");
                params.put("PDNO", "");
                params.put("CCLD_DVSN", "00");
                params.put("ORD_GNO_BRNO", "");
                params.put("ODNO", "");
                params.put("INQR_DVSN", "00");
                params.put("INQR_DVSN_1", "");
                params.put("INQR_DVSN_3", "00");
                params.put("EXCG_ID_DVSN_CD", "KRX");
                params.put("CTX_AREA_FK100", fk);
                params.put("CTX_AREA_NK100", nk);
                return rawExchange("/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                        params, TrId.DAILY_CCLD, cont);
            });
            JsonNode json = parse(page.body());
            requireOk(json);
            for (JsonNode row : json.path("output1")) {
                result.add(toSnapshot(row));
            }
            trCont = page.headers().getFirst("tr_cont");
            more = "M".equals(trCont);
            if (more) {
                ctxFk = strictText(json.path("ctx_area_fk100"), "ctx_area_fk100");
                ctxNk = strictText(json.path("ctx_area_nk100"), "ctx_area_nk100");
            }
        }
        return result;
    }

    @Override
    public Optional<KisOrderSnapshot> orderSnapshot(String kisOrderNo, LocalDate tradingDate) {
        return dailyOrders(tradingDate).stream()
                .filter(s -> s.kisOrderNo().equals(kisOrderNo))
                .findFirst();
    }

    @Override
    public KisBalance balance() {
        var resp = queryWithRetry(() -> {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("CANO", props.accountNo());
            params.put("ACNT_PRDT_CD", props.accountProductCode());
            params.put("AFHR_FLPR_YN", "N");
            params.put("OFL_YN", "");
            params.put("INQR_DVSN", "02");
            params.put("UNPR_DVSN", "01");
            params.put("FUND_STTL_ICLD_YN", "N");
            params.put("FNCG_AMT_AUTO_RDPT_YN", "N");
            params.put("PRCS_DVSN", "01");
            params.put("CTX_AREA_FK100", "");
            params.put("CTX_AREA_NK100", "");
            return rawExchange("/uapi/domestic-stock/v1/trading/inquire-balance",
                    params, TrId.BALANCE, "");
        });
        JsonNode json = parse(resp.body());
        requireOk(json);
        long holdingQty = 0L;
        for (JsonNode row : json.path("output1")) {
            holdingQty += strictLong(row.path("hldg_qty"), "hldg_qty");
        }
        BigDecimal cash = BigDecimal.ZERO;
        JsonNode output2 = json.path("output2");
        if (output2.isArray() && !output2.isEmpty()) {
            cash = strictDecimal(output2.get(0).path("dnca_tot_amt"), "dnca_tot_amt");
        } else if (output2.isObject()) {
            cash = strictDecimal(output2.path("dnca_tot_amt"), "dnca_tot_amt");
        }
        return new KisBalance(holdingQty, cash);
    }

    private KisOrderSnapshot toSnapshot(JsonNode row) {
        String odno = strictText(row.path("odno"), "odno");
        long orderQty = strictLong(row.path("ord_qty"), "ord_qty");
        long filledQty = strictLong(row.path("tot_ccld_qty"), "tot_ccld_qty");
        BigDecimal avgPrice = strictDecimal(row.path("avg_prvs"), "avg_prvs");
        long rmnQty = strictLong(row.path("rmn_qty"), "rmn_qty");
        String cnclYn = strictText(row.path("cncl_yn"), "cncl_yn");
        boolean cancelled = "Y".equals(cnclYn);
        boolean brokerTerminal = (rmnQty == 0) || cancelled;
        // 방어적 파싱 — 필드 부재/공백이면 null(ackFrom과 동일 규칙, Fix A 2차 리뷰). 복구·채택
        // 경로가 제출 ack 없이 이 조회만으로 주문을 알게 될 때 org no의 유일한 출처가 된다.
        String krxFwdgOrdOrgno = orNullIfBlank(textOrNull(row.path("krx_fwdg_ord_orgno")));
        return new KisOrderSnapshot(odno, orderQty, filledQty, avgPrice, brokerTerminal, cancelled,
                krxFwdgOrdOrgno);
    }

    /**
     * 조회(GET) 공통 경로 — 401이면 토큰 무효화 후 1회 재시도. 주문 POST는 재시도하지 않는다.
     * 재시도 후에도 401이면 내부 전용 Unauthorized를 그대로 흘리지 않고 의미있는 메시지의
     * KisTransportException으로 감싼다 — 응답을 받았다는 것은 요청이 송신되었다는 뜻이므로
     * requestSent=true.
     */
    private RawResponse queryWithRetry(java.util.function.Supplier<RawResponse> call) {
        try {
            return callOnce(call);
        } catch (Unauthorized first) {
            tokenManager.invalidate();
            try {
                return callOnce(call);
            } catch (Unauthorized second) {
                throw new KisTransportException(
                        "KIS query rejected with 401 after token invalidate+retry", true, second);
            }
        }
    }

    private RawResponse callOnce(java.util.function.Supplier<RawResponse> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new KisTransportException("query failed", !KisTokenManager.isConnectFailure(e), e);
        }
    }

    private RawResponse rawExchange(String path, Map<String, String> params, String trId, String trCont) {
        String token = tokenManager.accessToken();
        var result = rest.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path(path);
                    params.forEach(b::queryParam);
                    return b.build();
                })
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("appkey", props.appKey());
                    h.set("appsecret", props.appSecret());
                    h.set("tr_id", trId);
                    if (!trCont.isEmpty()) {
                        h.set("tr_cont", trCont);
                    }
                })
                .exchange((req, res) -> {
                    if (res.getStatusCode().value() == 401) {
                        throw new Unauthorized();
                    }
                    String bodyStr = new String(res.getBody().readAllBytes());
                    return new RawResponse(bodyStr, res.getHeaders());
                });
        return result;
    }

    private void orderHeaders(HttpHeaders h, String token, String trId, String hashKey) {
        h.setBearerAuth(token);
        h.set("appkey", props.appKey());
        h.set("appsecret", props.appSecret());
        h.set("tr_id", trId);
        h.set("hashkey", hashKey);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    }

    private String hashKey(Map<String, Object> body) {
        Map<?, ?> resp = rest.post().uri("/uapi/hashkey")
                .headers(h -> {
                    h.set("appkey", props.appKey());
                    h.set("appsecret", props.appSecret());
                })
                .body(body)
                .retrieve().body(Map.class);
        Object hash = resp.get("HASH");
        if (hash == null || hash.toString().isBlank()) {
            throw new KisTransportException("hashkey response missing HASH", false, null);
        }
        return hash.toString();
    }

    private void requireOk(JsonNode json) {
        String rtCd = textOrNull(json.path("rt_cd"));
        if (!"0".equals(rtCd)) {
            throw new KisOrderRejectedException(rtCd, textOrNull(json.path("msg1")));
        }
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (JacksonException e) {
            throw new IllegalStateException("KIS response parse failed: " + e.getMessage(), e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    /** strict 파싱 — 빈/공백 문자열이면 예외(조용한 0/누락 금지). */
    private static String strictText(JsonNode node, String field) {
        if (!node.isString() || node.asString().isBlank()) {
            throw new IllegalStateException("KIS response field missing/blank: " + field);
        }
        return node.asString();
    }

    private static long strictLong(JsonNode node, String field) {
        String text = strictText(node, field);
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("KIS response field not numeric: " + field + "=" + text, e);
        }
    }

    private static BigDecimal strictDecimal(JsonNode node, String field) {
        String text = strictText(node, field);
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("KIS response field not numeric: " + field + "=" + text, e);
        }
    }

    private record RawResponse(String body, HttpHeaders headers) {}

    private static final class Unauthorized extends RuntimeException {}
}
