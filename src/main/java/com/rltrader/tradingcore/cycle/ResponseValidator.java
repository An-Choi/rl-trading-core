package com.rltrader.tradingcore.cycle;

import com.rltrader.tradingcore.domain.TradeAction;
import com.rltrader.tradingcore.domain.TradingGrid;
import com.rltrader.tradingcore.predict.ArtifactContext;
import com.rltrader.tradingcore.predict.PredictResponse;
import java.time.ZonedDateTime;
import java.util.Optional;

public final class ResponseValidator {

    public record Violation(String alertCode, String reason) {}

    private ResponseValidator() {}

    public static Optional<Violation> validate(PredictResponse r,
            ZonedDateTime scheduledBar, ArtifactContext ctx) {
        // ① 구조·건전성 — null 필드는 NPE가 아니라 INVALID_RESPONSE로 (fail-closed 경로 유지)
        if (r.barTs() == null) {
            return violation("INVALID_RESPONSE", "bar_ts missing");
        }
        if (!Double.isFinite(r.barClose()) || r.barClose() <= 0) {
            return violation("INVALID_RESPONSE", "bar_close not finite positive: " + r.barClose());
        }
        if (r.actionMask() == null || r.actionMask().size() != 3
                || r.actionMask().stream().anyMatch(java.util.Objects::isNull)) {
            return violation("INVALID_RESPONSE", "action_mask malformed: " + r.actionMask());
        }
        Optional<TradeAction> action = TradeAction.fromIndex(r.action());
        if (action.isEmpty()) {
            return violation("INVALID_RESPONSE", "action out of range: " + r.action());
        }
        if (!action.get().label().equals(r.label())) {
            return violation("INVALID_RESPONSE",
                    "label mismatch: action=" + r.action() + " label=" + r.label());
        }
        if (r.observation() == null || r.observation().size() != 13
                || r.observation().stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
            return violation("INVALID_RESPONSE", "observation must be 13 finite numbers");
        }
        // ② artifact 고정
        if (!ctx.artifactId().equals(r.artifactId())
                || ctx.featureSchemaVersion() != r.featureSchemaVersion()) {
            return violation("ARTIFACT_CHANGED", "artifact " + r.artifactId()
                    + "/fs" + r.featureSchemaVersion() + " != handshake " + ctx.artifactId());
        }
        // ③ 기대 bar 일치 (staleness 허용창의 직전 bar 방어)
        ZonedDateTime responseBar = r.barTs().atZoneSameInstant(TradingGrid.KST);
        if (!responseBar.isEqual(scheduledBar)) {
            return violation("WRONG_BAR",
                    "response bar " + responseBar + " != scheduled " + scheduledBar);
        }
        return Optional.empty();
    }

    private static Optional<Violation> violation(String code, String reason) {
        return Optional.of(new Violation(code, reason));
    }
}
