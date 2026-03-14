package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MetricsBenchResult(
        @JsonProperty(required = true, value = "toolName") String toolName,
        @JsonProperty(required = true, value = "roundtripMs") long roundtripMs,
        @JsonProperty(required = true, value = "estimatedPayloadTokens") int estimatedPayloadTokens,
        @JsonProperty(required = true, value = "responseBytes") int responseBytes,
        @JsonProperty(required = true, value = "status") String status,
        @JsonProperty(required = true, value = "topLevelKeys") List<String> topLevelKeys,
        @JsonProperty(required = true, value = "preview") String preview) {
}