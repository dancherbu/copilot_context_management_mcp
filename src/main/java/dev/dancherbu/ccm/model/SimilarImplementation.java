package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SimilarImplementation(
        @JsonProperty(required = true, value = "filePath") String filePath,
        @JsonProperty(required = true, value = "symbolName") String symbolName,
        @JsonProperty(required = true, value = "lineRange") String lineRange,
        @JsonProperty(required = true, value = "reason") String reason,
        @JsonProperty(required = true, value = "snippetPreview") String snippetPreview) {
}