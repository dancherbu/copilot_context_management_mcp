package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TestObligations(
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "sourceFiles") List<String> sourceFiles,
        @JsonProperty(required = true, value = "existingTests") List<String> existingTests,
        @JsonProperty(required = true, value = "missingTests") List<String> missingTests,
        @JsonProperty(required = true, value = "suggestedScenarios") List<String> suggestedScenarios,
        @JsonProperty(required = true, value = "verificationCommands") List<String> verificationCommands,
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