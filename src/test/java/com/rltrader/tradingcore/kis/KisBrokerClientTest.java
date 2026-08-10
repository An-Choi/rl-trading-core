package com.rltrader.tradingcore.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.rltrader.tradingcore.config.KisProperties;
import com.rltrader.tradingcore.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

class KisBrokerClientTest {

    static WireMockServer wm = new WireMockServer(0);
    KisBrokerClient client;

    @BeforeAll static void up() { wm.start(); }
    @AfterAll  static void down() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(okJson("{\"access_token\":\"T\",\"expires_in\":86400}")));
        wm.stubFor(post(urlEqualTo("/uapi/hashkey"))
                .willReturn(okJson("{\"HASH\":\"h1\"}")));
        KisProperties props = new KisProperties("http://localhost:" + wm.port(),
                "AK", "AS", "12345678", "01", 1000, 2000);
        client = new KisBrokerClient(props, new KisTokenManager(props, Clock.systemUTC()),
                new tools.jackson.databind.json.JsonMapper());
    }

    @Test
    void submit_buy_returns_ack() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withHeader("tr_id", equalTo("VTTC0012U"))
                .willReturn(okJson("""
                    {"rt_cd":"0","output":{"ODNO":"0000012345","KRX_FWDG_ORD_ORGNO":"05360"}}""")));
        KisOrderAck ack = client.submitMarketOrder("005930", OrderSide.BUY, 10);
        assertThat(ack.kisOrderNo()).isEqualTo("0000012345");
        assertThat(ack.krxFwdgOrdOrgno()).isEqualTo("05360");
        wm.verify(postRequestedFor(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("01")))  // 시장가
                .withRequestBody(matchingJsonPath("$.EXCG_ID_DVSN_CD", equalTo("KRX"))));  // Fix B
    }

    // Fix B(2차 리뷰) — 매도도 공식 샘플의 전용 TR ID(VTTC0011U)를 쓴다(매수 VTTC0012U와 별개).
    @Test
    void submit_sell_uses_sell_tr_id() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withHeader("tr_id", equalTo("VTTC0011U"))
                .willReturn(okJson("""
                    {"rt_cd":"0","output":{"ODNO":"0000054321","KRX_FWDG_ORD_ORGNO":"05360"}}""")));
        KisOrderAck ack = client.submitMarketOrder("005930", OrderSide.SELL, 10);
        assertThat(ack.kisOrderNo()).isEqualTo("0000054321");
    }

    // Fix 1 — 원주문 ack에 KRX_FWDG_ORD_ORGNO가 없거나 공백이면 방어적으로 null(부재는
    // 증명이 아니다 — 취소 시 공백 전송으로 폴백).
    @Test
    void submit_buy_without_org_no_in_ack_parses_as_null() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withHeader("tr_id", equalTo("VTTC0012U"))
                .willReturn(okJson("{\"rt_cd\":\"0\",\"output\":{\"ODNO\":\"0000012345\"}}")));
        KisOrderAck ack = client.submitMarketOrder("005930", OrderSide.BUY, 10);
        assertThat(ack.kisOrderNo()).isEqualTo("0000012345");
        assertThat(ack.krxFwdgOrdOrgno()).isNull();
    }

    @Test
    void broker_rejection_raises_typed_exception() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .willReturn(okJson("{\"rt_cd\":\"1\",\"msg_cd\":\"40250000\",\"msg1\":\"증거금 부족\"}")));
        assertThatThrownBy(() -> client.submitMarketOrder("005930", OrderSide.BUY, 10))
                .isInstanceOf(KisOrderRejectedException.class);
    }

    @Test
    void daily_orders_follows_pagination() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
                .inScenario("page").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("""
                    {"rt_cd":"0","ctx_area_fk100":"K1","ctx_area_nk100":"N1",
                     "output1":[{"odno":"01","ord_qty":"10","tot_ccld_qty":"10",
                                 "avg_prvs":"100.0","rmn_qty":"0","cncl_yn":"N"}]}""")
                        .withHeader("tr_cont", "M"))
                .willSetStateTo("page2"));
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
                .inScenario("page").whenScenarioStateIs("page2")
                .willReturn(okJson("""
                    {"rt_cd":"0","ctx_area_fk100":"","ctx_area_nk100":"",
                     "output1":[{"odno":"02","ord_qty":"5","tot_ccld_qty":"2",
                                 "avg_prvs":"99.5","rmn_qty":"3","cncl_yn":"N"}]}""")
                        .withHeader("tr_cont", "D")));
        var list = client.dailyOrders(LocalDate.of(2026, 8, 7));
        assertThat(list).hasSize(2);
        assertThat(list.get(1).cumulativeFilledQty()).isEqualTo(2L);
        assertThat(list.get(1).brokerTerminal()).isFalse();
        assertThat(list.get(1).cumulativeAvgPrice()).isEqualByComparingTo(new BigDecimal("99.5"));
    }

    // Fix(3차 리뷰, P1) — 일별주문체결(inquire-daily-ccld) 공식 스키마의 조직번호 필드는
    // ord_gno_brno다(제출 ack의 KRX_FWDG_ORD_ORGNO와는 다른 필드). 실제 JSON 파싱 경로를 거쳐야
    // 이 필드명 버그를 잡을 수 있다 — KisOrderSnapshot을 직접 생성하는 테스트는 이 경로를
    // 검증하지 못한다.
    @Test
    void daily_orders_reads_org_no_from_ord_gno_brno() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
                .willReturn(okJson("""
                    {"rt_cd":"0","ctx_area_fk100":"","ctx_area_nk100":"",
                     "output1":[{"odno":"01","ord_qty":"10","tot_ccld_qty":"10",
                                 "avg_prvs":"100.0","rmn_qty":"0","cncl_yn":"N",
                                 "ord_gno_brno":"05360"}]}""")
                        .withHeader("tr_cont", "D")));
        var list = client.dailyOrders(LocalDate.of(2026, 8, 7));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).krxFwdgOrdOrgno()).isEqualTo("05360");
    }

    // ord_gno_brno가 없으면(부재/공백) null — 공식 스키마상 필드가 없는 경우를 대비한 방어적 파싱.
    @Test
    void daily_orders_without_org_no_field_parses_as_null() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
                .willReturn(okJson("""
                    {"rt_cd":"0","ctx_area_fk100":"","ctx_area_nk100":"",
                     "output1":[{"odno":"01","ord_qty":"10","tot_ccld_qty":"10",
                                 "avg_prvs":"100.0","rmn_qty":"0","cncl_yn":"N"}]}""")
                        .withHeader("tr_cont", "D")));
        var list = client.dailyOrders(LocalDate.of(2026, 8, 7));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).krxFwdgOrdOrgno()).isNull();
    }

    @Test
    void balance_maps_holding_and_cash() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .willReturn(okJson("""
                    {"rt_cd":"0",
                     "output1":[{"pdno":"005930","hldg_qty":"15"}],
                     "output2":[{"dnca_tot_amt":"9876543.00"}]}""")));
        KisBalance b = client.balance();
        assertThat(b.holdingQty()).isEqualTo(15L);
        assertThat(b.cashBalance()).isEqualByComparingTo(new BigDecimal("9876543.00"));
    }

    // 브리프 제약: hashkey 발급 단계 실패는 주문이 송신되지 않았으므로 requestSent는
    // 무조건 false여야 한다 (SUBMIT_UNKNOWN 오분류 방지) — 주문 POST 자체의 전송 실패와 구분.
    @Test
    void hashkey_failure_is_request_not_sent() {
        wm.stubFor(post(urlEqualTo("/uapi/hashkey"))
                .willReturn(aResponse().withFixedDelay(5000).withStatus(200)));
        assertThatThrownBy(() -> client.submitMarketOrder("005930", OrderSide.BUY, 10))
                .isInstanceOf(KisTransportException.class)
                .satisfies(e -> assertThat(((KisTransportException) e).requestSent()).isFalse());
    }

    // 리뷰 finding 1: hashkey가 non-2xx(HTTP 오류 상태)를 반환하면 .retrieve()가
    // RestClientResponseException을 던진다 — 이 경로도 hashkey 실패이므로 requestSent=false.
    @Test
    void hashkey_http_error_status_is_request_not_sent() {
        wm.stubFor(post(urlEqualTo("/uapi/hashkey"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        assertThatThrownBy(() -> client.submitMarketOrder("005930", OrderSide.BUY, 10))
                .isInstanceOf(KisTransportException.class)
                .satisfies(e -> assertThat(((KisTransportException) e).requestSent()).isFalse());
    }

    // 리뷰 finding 1: 주문 POST 자체가 non-2xx를 반환하면 — 응답을 받았다는 것은 요청이
    // 실제로 송신되었다는 뜻이므로 requestSent=true.
    @Test
    void order_post_http_error_status_is_request_sent() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        assertThatThrownBy(() -> client.submitMarketOrder("005930", OrderSide.BUY, 10))
                .isInstanceOf(KisTransportException.class)
                .satisfies(e -> assertThat(((KisTransportException) e).requestSent()).isTrue());
    }

    @Test
    void cancel_order_sends_expected_body_and_succeeds() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .withHeader("tr_id", equalTo("VTTC0013U"))
                .willReturn(okJson("{\"rt_cd\":\"0\",\"output\":{\"ODNO\":\"0000099999\"}}")));
        client.cancelOrder("0000012345", 5, "05360");
        wm.verify(postRequestedFor(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .withRequestBody(matchingJsonPath("$.ORGN_ODNO", equalTo("0000012345")))
                .withRequestBody(matchingJsonPath("$.RVSE_CNCL_DVSN_CD", equalTo("02")))
                .withRequestBody(matchingJsonPath("$.ORD_QTY", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("00")))
                .withRequestBody(matchingJsonPath("$.EXCG_ID_DVSN_CD", equalTo("KRX")))
                .withRequestBody(matchingJsonPath("$.KRX_FWDG_ORD_ORGNO", equalTo("05360"))));
    }

    // Fix 1 — 원주문 ack에 조직번호가 없었던 경우(null) 취소 요청은 공백을 전송한다.
    @Test
    void cancel_order_with_no_org_no_sends_blank() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .withHeader("tr_id", equalTo("VTTC0013U"))
                .willReturn(okJson("{\"rt_cd\":\"0\",\"output\":{\"ODNO\":\"0000099999\"}}")));
        client.cancelOrder("0000012345", 5, null);
        wm.verify(postRequestedFor(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .withRequestBody(matchingJsonPath("$.KRX_FWDG_ORD_ORGNO", equalTo(""))));
    }

    @Test
    void cancel_order_rejection_raises_typed_exception() {
        wm.stubFor(post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .willReturn(okJson("{\"rt_cd\":\"1\",\"msg_cd\":\"40250099\",\"msg1\":\"취소 불가\"}")));
        assertThatThrownBy(() -> client.cancelOrder("0000012345", 5, "05360"))
                .isInstanceOf(KisOrderRejectedException.class);
    }

    // 리뷰 finding 4: 조회 401 -> invalidate() -> 재시도 성공 (1회만 재시도되는지 확인).
    @Test
    void query_retries_once_after_401_then_succeeds() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .inScenario("auth").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .inScenario("auth").whenScenarioStateIs("retried")
                .willReturn(okJson("""
                    {"rt_cd":"0",
                     "output1":[{"pdno":"005930","hldg_qty":"15"}],
                     "output2":[{"dnca_tot_amt":"9876543.00"}]}""")));
        KisBalance b = client.balance();
        assertThat(b.holdingQty()).isEqualTo(15L);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance")));
    }

    // 리뷰 finding 2: 재시도 후에도 401이면 내부 private Unauthorized가 새어나가지 않고
    // 의미있는 메시지의 KisTransportException(requestSent=true)으로 감싸져야 한다.
    @Test
    void query_persistent_401_raises_typed_transport_exception() {
        wm.stubFor(get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.balance())
                .isInstanceOf(KisTransportException.class)
                .satisfies(e -> assertThat(((KisTransportException) e).requestSent()).isTrue());
        wm.verify(2, getRequestedFor(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance")));
    }
}
