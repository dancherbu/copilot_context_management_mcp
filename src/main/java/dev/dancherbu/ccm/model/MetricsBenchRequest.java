package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MetricsBenchRequest(
        @JsonProperty(required = true, value = "apiKey") String apiKey,
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "tools") List<String> tools,
        @JsonProperty(required = false, value = "projectName") String projectName) {
}