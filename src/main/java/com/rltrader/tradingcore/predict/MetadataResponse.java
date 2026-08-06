package com.rltrader.tradingcore.predict;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record MetadataResponse(
        @JsonProperty("artifact_id") String artifactId,
        @JsonProperty("feature_schema_version") int featureSchemaVersion,
        @JsonProperty("action_space") Map<String, Object> actionSpace,
        @JsonProperty("env_params") Map<String, Object> envParams,
        @JsonProperty("train_git_sha") String trainGitSha) {
}
