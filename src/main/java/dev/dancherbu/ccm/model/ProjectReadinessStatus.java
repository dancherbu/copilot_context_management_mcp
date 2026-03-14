package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProjectReadinessStatus(
        @JsonProperty(required = true, value = "projectName") String projectName,
        @JsonProperty(required = true, value = "projectPath") String projectPath,
        @JsonProperty(required = true, value = "ready") boolean ready,
        @JsonProperty(required = true, value = "fileCoveragePercent") double fileCoveragePercent,
        @JsonProperty(required = true, value = "chunkCoveragePercent") double chunkCoveragePercent,
        @JsonProperty(required = true, value = "semanticCoveragePercent") double semanticCoveragePercent,
        @JsonProperty(required = true, value = "thresholdPercent") double thresholdPercent,
        @JsonProperty(required = true, value = "warnings") List<String> warnings,
        @JsonProperty(required = true, value = "sampleIndexedFiles") List<String> sampleIndexedFiles) {
}