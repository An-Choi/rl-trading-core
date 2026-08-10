package com.rltrader.tradingcore.kis;

import com.rltrader.tradingcore.config.KisProperties;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** access token 24h — 23h 캐시 후 재발급 (여유 1h). PredictClient의 RestClient 구성 관례 재사용. */
@Component
public class KisTokenManager {

    private final RestClient rest;
    private final KisProperties props;
    private final Clock clock;
    private volatile String token;
    private volatile Instant issuedAt;

    public KisTokenManager(KisProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs())).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    public synchronized String accessToken() {
        if (token != null && issuedAt.plus(Duration.ofHours(23)).isAfter(clock.instant())) {
            return token;
        }
        try {
            Map<?, ?> body = rest.post().uri("/oauth2/tokenP")
                    .body(Map.of("grant_type", "client_credentials",
                            "appkey", props.appKey(), "appsecret", props.appSecret()))
                    .retrieve().body(Map.class);
            token = (String) body.get("access_token");
            issuedAt = clock.instant();
            return token;
        } catch (ResourceAccessException e) {
            throw new KisTransportException("token issue failed", !isConnectFailure(e), e);
        }
    }

    public synchronized void invalidate() { token = null; }

    static boolean isConnectFailure(ResourceAccessException e) {
        Throwable c = e.getCause();
        return c instanceof ConnectException || c instanceof HttpConnectTimeoutException;
    }
}
