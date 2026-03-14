package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record IndexCoverageFile(
        @JsonProperty(required = true, value = "relativePath") String relativePath,
        @JsonProperty(required = true, value = "language") String language,
        @JsonProperty(required = true, value = "indexed") boolean indexed,
        @JsonProperty(required = true, value = "totalChunks") int totalChunks,
        @JsonProperty(required = true, value = "embeddedChunks") int embeddedChunks,
        @JsonProperty(required = true, value = "chunkCoveragePercent") double chunkCoveragePercent,
        @JsonProperty(required = true, value = "totalSemanticNodes") int totalSemanticNodes,
        @JsonProperty(required = true, value = "embeddedSemanticNodes") int embeddedSemanticNodes,
        @JsonProperty(required = true, value = "semanticCoveragePercent") double semanticCoveragePercent,
        @JsonProperty(required = true, value = "lastIndexedAt") String lastIndexedAt,
        @JsonProperty(required = true, value = "embeddedSymbols") List<String> embeddedSymbols,
        @JsonProperty(required = true, value = "missingSymbols") List<String> missingSymbols) {
}