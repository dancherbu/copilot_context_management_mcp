package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ExecutionBrief(
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "objective") String objective,
        @JsonProperty(required = true, value = "likelyEntrypoints") List<String> likelyEntrypoints,
        @JsonProperty(required = true, value = "filesToInspect") List<String> filesToInspect,
        @JsonProperty(required = true, value = "filesToEdit") List<String> filesToEdit,
        @JsonProperty(required = true, value = "supportingFiles") List<String> supportingFiles,
        @JsonProperty(required = true, value = "testsToUpdate") List<String> testsToUpdate,
        @JsonProperty(required = true, value = "constraints") List<String> constraints,
        @JsonProperty(required = true, value = "executionSteps") List<String> executionSteps,
        @JsonProperty(required = true, value = "handoffPrompt") String handoffPrompt,
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