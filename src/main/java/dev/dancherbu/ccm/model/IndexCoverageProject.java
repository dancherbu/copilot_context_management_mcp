package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record IndexCoverageProject(
        @JsonProperty(required = true, value = "projectName") String projectName,
        @JsonProperty(required = true, value = "projectPath") String projectPath,
        @JsonProperty(required = true, value = "embeddingCoverageThresholdPercent") double embeddingCoverageThresholdPercent,
        @JsonProperty(required = true, value = "embeddingCoverageThresholdMet") boolean embeddingCoverageThresholdMet,
        @JsonProperty(required = true, value = "embeddingCoverageShortfallPercent") double embeddingCoverageShortfallPercent,
        @JsonProperty(required = true, value = "fileCoverageThresholdPercent") double fileCoverageThresholdPercent,
        @JsonProperty(required = true, value = "fileCoverageThresholdMet") boolean fileCoverageThresholdMet,
        @JsonProperty(required = true, value = "fileCoverageThresholdShortfallPercent") double fileCoverageThresholdShortfallPercent,
        @JsonProperty(required = true, value = "chunkCoverageThresholdPercent") double chunkCoverageThresholdPercent,
        @JsonProperty(required = true, value = "chunkCoverageThresholdMet") boolean chunkCoverageThresholdMet,
        @JsonProperty(required = true, value = "chunkCoverageThresholdShortfallPercent") double chunkCoverageThresholdShortfallPercent,
        @JsonProperty(required = true, value = "semanticCoverageThresholdPercent") double semanticCoverageThresholdPercent,
        @JsonProperty(required = true, value = "semanticCoverageThresholdMet") boolean semanticCoverageThresholdMet,
        @JsonProperty(required = true, value = "semanticCoverageThresholdShortfallPercent") double semanticCoverageThresholdShortfallPercent,
        @JsonProperty(required = true, value = "warnings") List<String> warnings,
        @JsonProperty(required = true, value = "indexableFiles") int indexableFiles,
        @JsonProperty(required = true, value = "embeddedFiles") int embeddedFiles,
        @JsonProperty(required = true, value = "fileCoveragePercent") double fileCoveragePercent,
        @JsonProperty(required = true, value = "totalChunks") int totalChunks,
        @JsonProperty(required = true, value = "embeddedChunks") int embeddedChunks,
        @JsonProperty(required = true, value = "chunkCoveragePercent") double chunkCoveragePercent,
        @JsonProperty(required = true, value = "totalSemanticNodes") int totalSemanticNodes,
        @JsonProperty(required = true, value = "embeddedSemanticNodes") int embeddedSemanticNodes,
        @JsonProperty(required = true, value = "semanticCoveragePercent") double semanticCoveragePercent,
        @JsonProperty(required = true, value = "lastIndexedAt") String lastIndexedAt,
        @JsonProperty(required = true, value = "nodeTypeCoverage") List<IndexCoverageNodeType> nodeTypeCoverage,
        @JsonProperty(required = true, value = "files") List<IndexCoverageFile> files) {
}