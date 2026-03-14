package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ImpactAnalysisResult(
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "workspaceRoot") String workspaceRoot,
        @JsonProperty(required = true, value = "impacts") List<FileImpact> impacts,
        @JsonProperty(required = true, value = "responseMode") String responseMode,
        @JsonProperty(required = true, value = "estimatedPayloadTokens") int estimatedPayloadTokens,
        @JsonProperty(required = true, value = "estimatedPromptTokens") int estimatedPromptTokens,
        @JsonProperty(required = true, value = "suggestedTrimPlan") List<String> suggestedTrimPlan,
        @JsonProperty(required = true, value = "contextHash") String contextHash,
        @JsonProperty(required = true, value = "unchanged") boolean unchanged,
        @JsonProperty(required = true, value = "cacheHit") boolean cacheHit,
        @JsonProperty(required = true, value = "cacheAgeSec") int cacheAgeSec,
        @JsonProperty(required = true, value = "sourceOfTruth") String sourceOfTruth) {
}
