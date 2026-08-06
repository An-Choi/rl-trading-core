package com.rltrader.tradingcore.predict;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rltrader.tradingcore.config.JacksonConfig;
import com.rltrader.tradingcore.config.TradingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PredictClientTest {

    private WireMockServer server;
    private PredictClient client;

    private static final String OK_BODY = """
            {"action": 1, "label": "add_unit", "action_mask": [true, true, false],
             "bar_ts": "2026-08-06T11:00:00+09:00",
             "artifact_id": "ppo-fs3-x", "feature_schema_version": 3,
             "observation": [0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,0.0,0.0,0.0,0.5],
             "bar_close": 71000.0}""";

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        TradingProperties props = new TradingProperties(
                "005930", "http://127.0.0.1:" + server.port(),
                20, 5, 10_000.0, 10_000.0, 1000, 2000, 1000, 5);
        client = new PredictClient(props, JacksonConfig.strictMapper());
    }

    @AfterEach
    void tearDown() { server.stop(); }

    @Test
    void predictParsesSuccessResponse() {
        server.stubFor(post("/predict").willReturn(okJson(OK_BODY)));
        PredictResult result = client.predict(PredictRequest.flat("005930", 10_000.0, null));
        assertThat(result.body().action()).isEqualTo(1);
        assertThat(result.body().barClose()).isEqualTo(71_000.0);
        assertThat(result.body().observation()).hasSize(13);
        assertThat(result.latencyMs()).isNotNegative();
    }

    @Test
    void asOfOmittedWhenNull() {
        server.stubFor(post("/predict").willReturn(okJson(OK_BODY)));
        client.predict(PredictRequest.flat("005930", 10_000.0, null));
        server.verify(postRequestedFor(urlEqualTo("/predict"))
                .withRequestBody(notMatching(".*as_of.*")));
    }

    @Test
    void serverErrorCodeIsMapped() {
        server.stubFor(post("/predict").willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"code\": \"STALE_DATA\", \"message\": \"m\", \"detail\": {}}}")));
        assertThatThrownBy(() -> client.predict(PredictRequest.flat("005930", 10_000.0, null)))
                .isInstanceOfSatisfying(PredictFailureException.class,
                        e -> assertThat(e.code()).isEqualTo("STALE_DATA"));
    }

    @Test
    void stringNumberCoercionRejected() {
        // strict mapper: "71000.0" 문자열 → double 강제 변환 금지 (spec §2 검증 ①)
        String coerced = OK_BODY.replace("\"bar_close\": 71000.0", "\"bar_close\": \"71000.0\"");
        server.stubFor(post("/predict").willReturn(okJson(coerced)));
        assertThatThrownBy(() -> client.predict(PredictRequest.flat("005930", 10_000.0, null)))
                .isInstanceOfSatisfying(PredictFailureException.class,
                        e -> assertThat(e.code()).isEqualTo("PARSE"));
    }

    @Test
    void connectionRefusedMapsToConnection() {
        server.stop();
        assertThatThrownBy(() -> client.predict(PredictRequest.flat("005930", 10_000.0, null)))
                .isInstanceOfSatisfying(PredictFailureException.class,
                        e -> assertThat(e.code()).isEqualTo("CONNECTION"));
    }

    @Test
    void unknownPropertyIsAllowed() {
        // additive 계약: 서버가 새 필드를 추가해도 unknown property로 실패하면 안 됨 (spec §2 검증 ①)
        String withUnknownField = OK_BODY.replace(
                "\"bar_close\": 71000.0", "\"bar_close\": 71000.0, \"future_field\": 1");
        server.stubFor(post("/predict").willReturn(okJson(withUnknownField)));
        PredictResult result = client.predict(PredictRequest.flat("005930", 10_000.0, null));
        assertThat(result.body().action()).isEqualTo(1);
        assertThat(result.body().barClose()).isEqualTo(71_000.0);
    }

    @Test
    void slowResponseMapsToTimeout() {
        // 테스트 props의 readTimeoutMs=2000ms보다 긴 지연 — read timeout 경로 검증
        server.stubFor(post("/predict").willReturn(okJson(OK_BODY).withFixedDelay(3000)));
        assertThatThrownBy(() -> client.predict(PredictRequest.flat("005930", 10_000.0, null)))
                .isInstanceOfSatisfying(PredictFailureException.class,
                        e -> assertThat(e.code()).isEqualTo("TIMEOUT"));
    }

    @Test
    void numberToBooleanCoercionRejected() {
        // strict mapper: action_mask의 정수(1/0) → boolean 강제 변환 금지 (spec §2 검증 ①)
        String coerced = OK_BODY.replace(
                "\"action_mask\": [true, true, false]", "\"action_mask\": [1, 0, 1]");
        server.stubFor(post("/predict").willReturn(okJson(coerced)));
        assertThatThrownBy(() -> client.predict(PredictRequest.flat("005930", 10_000.0, null)))
                .isInstanceOfSatisfying(PredictFailureException.class,
                        e -> assertThat(e.code()).isEqualTo("PARSE"));
    }
}
