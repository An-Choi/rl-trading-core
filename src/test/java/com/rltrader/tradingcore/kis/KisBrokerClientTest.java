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
                .withHeader("tr_id", equalTo("VTTC0802U"))
                .willReturn(okJson("{\"rt_cd\":\"0\",\"output\":{\"ODNO\":\"0000012345\"}}")));
        KisOrderAck ack = client.submitMarketOrder("005930", OrderSide.BUY, 10);
        assertThat(ack.kisOrderNo()).isEqualTo("0000012345");
        wm.verify(postRequestedFor(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("01"))));  // 시장가
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
}
