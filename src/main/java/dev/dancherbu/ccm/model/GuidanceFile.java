package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GuidanceFile(
        @JsonProperty(required = true, value = "filePath") String filePath,
        @JsonProperty(required = true, value = "category") String category,
        @JsonProperty(required = true, value = "summary") String summary,
        @JsonProperty(required = true, value = "excerpt") String excerpt) {
}