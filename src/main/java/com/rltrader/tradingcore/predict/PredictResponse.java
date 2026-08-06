package com.rltrader.tradingcore.predict;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public record PredictResponse(
        int action,
        String label,
        @JsonProperty("action_mask") List<Boolean> actionMask,
        @JsonProperty("bar_ts") OffsetDateTime barTs,
        @JsonProperty("artifact_id") String artifactId,
        @JsonProperty("feature_schema_version") int featureSchemaVersion,
        List<Double> observation,
        @JsonProperty("bar_close") double barClose) {
}
