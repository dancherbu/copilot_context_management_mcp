package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ToolCatalog(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "serverName") String serverName,
        @JsonProperty(required = true, value = "tools") List<String> tools,
        @JsonProperty(required = true, value = "agentToolIds") List<String> agentToolIds,
        @JsonProperty(required = true, value = "notes") List<String> notes) {
}
