package com.rltrader.tradingcore.predict;

import com.rltrader.tradingcore.config.TradingProperties;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Boot 4.1 적응: jackson-databind가 3.x(tools.jackson.databind)라 패키지 변경.
// 또한 Jackson 3의 readValue/readTree는 java.io.IOException이 아니라 unchecked
// JacksonException(tools.jackson.core)을 던지므로 catch 대상도 그에 맞춤.
@Component
public class PredictClient {

    private final RestClient predictRest;   // /predict 전용 — read timeout 240s
    private final RestClient controlRest;   // /ready·/metadata 전용 — 짧은 timeout
    private final ObjectMapper mapper;

    public PredictClient(TradingProperties props, ObjectMapper strictMapper) {
        // /ready 폴링이 predict용 240s read timeout에 묶이면 무응답 서버에서
        // 첫 호출이 ready-wait-max-seconds를 초과해 막힌다 — client 분리
        this.predictRest = buildClient(props.predictBaseUrl(),
                props.connectTimeoutMs(), props.readTimeoutMs());
        this.controlRest = buildClient(props.predictBaseUrl(),
                props.connectTimeoutMs(), props.controlTimeoutMs());
        this.mapper = strictMapper;
    }

    private static RestClient buildClient(String baseUrl, int connectMs, int readMs) {
        // connect timeout은 JDK HttpClient 수준에서만 설정 가능.
        // HTTP/1.1 강제: 기본 h2 업그레이드 시도가 WireMock(HTTP/1.1 only) 상대로
        // RST_STREAM/EOF 오류를 유발 — predict 서버도 HTTP/1.1이므로 안전.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean ready() {
        try {
            return controlRest.get().uri("/ready").retrieve().toBodilessEntity()
                    .getStatusCode().is2xxSuccessful();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public MetadataResponse metadata() {
        String body = controlRest.get().uri("/metadata").retrieve().body(String.class);
        return parse(body, MetadataResponse.class);
    }

    public PredictResult predict(PredictRequest request) {
        long started = System.nanoTime();
        String body;
        int status;
        try {
            var entity = predictRest.post().uri("/predict").body(request)
                    .exchange((req, res) -> new String[]{
                            String.valueOf(res.getStatusCode().value()),
                            new String(res.getBody().readAllBytes())});
            status = Integer.parseInt(entity[0]);
            body = entity[1];
        } catch (ResourceAccessException e) {
            String code = (e.getCause() instanceof HttpTimeoutException) ? "TIMEOUT" : "CONNECTION";
            throw new PredictFailureException(code, 0, e.getMessage());
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        if (status != 200) {
            throw new PredictFailureException(errorCode(body), status, body);
        }
        return new PredictResult(parse(body, PredictResponse.class), latencyMs);
    }

    // Note: PredictResult is a top-level record (predict/PredictResult.java), not nested here —
    // PredictClientTest references it unqualified in the same package without importing
    // PredictClient.PredictResult, so it must be top-level to resolve.
    private String errorCode(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode code = node.path("error").path("code");
            return code.isString() ? code.asString() : "UNKNOWN";
        } catch (JacksonException e) {
            return "PARSE";
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            T parsed = mapper.readValue(body, type);
            if (parsed == null) {   // JSON 리터럴 null 본문 — NPE 대신 PARSE 실패
                throw new PredictFailureException("PARSE", 200, "null body");
            }
            return parsed;
        } catch (JacksonException e) {
            throw new PredictFailureException("PARSE", 200, e.getMessage());
        }
    }
}
