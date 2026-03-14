package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProjectReadinessReport(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "requestedProjectName") String requestedProjectName,
        @JsonProperty(required = true, value = "embeddingCoverageThresholdPercent") double embeddingCoverageThresholdPercent,
        @JsonProperty(required = true, value = "fileCoverageThresholdPercent") double fileCoverageThresholdPercent,
        @JsonProperty(required = true, value = "chunkCoverageThresholdPercent") double chunkCoverageThresholdPercent,
        @JsonProperty(required = true, value = "semanticCoverageThresholdPercent") double semanticCoverageThresholdPercent,
        @JsonProperty(required = true, value = "hasReadyProject") boolean hasReadyProject,
        @JsonProperty(required = true, value = "suggestedProjectName") String suggestedProjectName,
        @JsonProperty(required = true, value = "suggestedResponseMode") String suggestedResponseMode,
        @JsonProperty(required = true, value = "suggestedTopK") int suggestedTopK,
        @JsonProperty(required = true, value = "suggestedMaxFiles") int suggestedMaxFiles,
        @JsonProperty(required = true, value = "readinessBlockers") List<String> readinessBlockers,
        @JsonProperty(required = true, value = "readyProjects") List<String> readyProjects,
        @JsonProperty(required = true, value = "blockedProjects") List<String> blockedProjects,
        @JsonProperty(required = true, value = "projects") List<ProjectReadinessStatus> projects,
        @JsonProperty(required = true, value = "snapshotWarnings") List<String> snapshotWarnings) {
}