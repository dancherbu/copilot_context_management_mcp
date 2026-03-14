package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChangeImpactTrace(
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "workspaceRoot") String workspaceRoot,
        @JsonProperty(required = true, value = "likelyImpactedFiles") List<String> likelyImpactedFiles,
        @JsonProperty(required = true, value = "matchedFiles") List<ContextFile> matchedFiles,
        @JsonProperty(required = true, value = "dependencyEdges") List<String> dependencyEdges,
        @JsonProperty(required = true, value = "relatedConfigs") List<String> relatedConfigs,
        @JsonProperty(required = true, value = "relatedTests") List<String> relatedTests,
        @JsonProperty(required = true, value = "operationalTouchpoints") List<String> operationalTouchpoints,
        @JsonProperty(required = true, value = "riskSummary") List<String> riskSummary,
        @JsonProperty(required = true, value = "estimatedPayloadTokens") int estimatedPayloadTokens,
        @JsonProperty(required = true, value = "responseMode") String responseMode,
        @JsonProperty(required = true, value = "estimatedPromptTokens") int estimatedPromptTokens,
        @JsonProperty(required = true, value = "suggestedTrimPlan") List<String> suggestedTrimPlan,
        @JsonProperty(required = true, value = "contextHash") String contextHash,
        @JsonProperty(required = true, value = "unchanged") boolean unchanged,
        @JsonProperty(required = true, value = "cacheHit") boolean cacheHit,
        @JsonProperty(required = true, value = "cacheAgeSec") int cacheAgeSec,
        @JsonProperty(required = true, value = "sourceOfTruth") String sourceOfTruth) {
}