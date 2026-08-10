package com.rltrader.tradingcore.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.rltrader.tradingcore.config.KisProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.*;

class KisTokenManagerTest {

    static WireMockServer wm = new WireMockServer(0);

    @BeforeAll static void up() { wm.start(); }
    @AfterAll  static void down() { wm.stop(); }
    @BeforeEach void reset() { wm.resetAll(); }

    private KisProperties props() {
        return new KisProperties("http://localhost:" + wm.port(), "AK", "AS",
                "12345678", "01", 1000, 2000);
    }

    @Test
    void issues_and_caches_token() {
        wm.stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(okJson(
                "{\"access_token\":\"T1\",\"expires_in\":86400}")));
        KisTokenManager tm = new KisTokenManager(props(),
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC));
        assertThat(tm.accessToken()).isEqualTo("T1");
        assertThat(tm.accessToken()).isEqualTo("T1");
        wm.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")));   // 캐시 — 1회만 발급
    }

    @Test
    void invalidate_forces_reissue() {
        wm.stubFor(post(urlEqualTo("/oauth2/tokenP")).willReturn(okJson(
                "{\"access_token\":\"T1\",\"expires_in\":86400}")));
        KisTokenManager tm = new KisTokenManager(props(),
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC));
        tm.accessToken();
        tm.invalidate();
        tm.accessToken();
        wm.verify(2, postRequestedFor(urlEqualTo("/oauth2/tokenP")));
    }

    @Test
    void connect_failure_is_request_not_sent() {
        KisProperties dead = new KisProperties("http://localhost:1", "AK", "AS",
                "12345678", "01", 300, 300);
        KisTokenManager tm = new KisTokenManager(dead, Clock.systemUTC());
        assertThatThrownBy(tm::accessToken)
                .isInstanceOf(KisTransportException.class)
                .satisfies(e -> assertThat(((KisTransportException) e).requestSent()).isFalse());
    }
}
