package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ContextFile(
        @JsonProperty(required = true, value = "filePath") String filePath,
        @JsonProperty(required = true, value = "reason") String reason,
        @JsonProperty(required = true, value = "matchedSymbols") List<String> matchedSymbols,
        @JsonProperty(required = true, value = "lineRange") String lineRange,
        @JsonProperty(required = true, value = "snippetPreview") String snippetPreview) {
}