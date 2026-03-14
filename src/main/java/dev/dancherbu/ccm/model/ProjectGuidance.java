package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProjectGuidance(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "projectName") String projectName,
        @JsonProperty(required = true, value = "projectPath") String projectPath,
        @JsonProperty(required = true, value = "guidanceReason") String guidanceReason,
        @JsonProperty(required = true, value = "discoveredFiles") List<GuidanceFile> discoveredFiles,
        @JsonProperty(required = true, value = "bestPractices") List<String> bestPractices,
        @JsonProperty(required = true, value = "codingStandards") List<String> codingStandards,
        @JsonProperty(required = true, value = "deploymentInstructions") List<String> deploymentInstructions,
        @JsonProperty(required = true, value = "secretLocations") List<String> secretLocations,
        @JsonProperty(required = true, value = "responseMode") String responseMode,
        @JsonProperty(required = true, value = "estimatedPromptTokens") int estimatedPromptTokens,
        @JsonProperty(required = true, value = "suggestedTrimPlan") List<String> suggestedTrimPlan,
        @JsonProperty(required = true, value = "guidanceHash") String guidanceHash,
        @JsonProperty(required = true, value = "unchanged") boolean unchanged,
        @JsonProperty(required = true, value = "cacheHit") boolean cacheHit,
        @JsonProperty(required = true, value = "cacheAgeSec") int cacheAgeSec,
        @JsonProperty(required = true, value = "sourceOfTruth") String sourceOfTruth,
        @JsonProperty(required = true, value = "includeSecrets") boolean includeSecrets,
        @JsonProperty(required = true, value = "riskLevel") String riskLevel) {
}