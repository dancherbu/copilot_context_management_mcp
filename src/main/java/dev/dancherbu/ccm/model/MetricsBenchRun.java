package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MetricsBenchRun(
        @JsonProperty(required = true, value = "runId") String runId,
        @JsonProperty(required = true, value = "recordedAt") String recordedAt,
        @JsonProperty(required = true, value = "query") String query,
        @JsonProperty(required = true, value = "tools") List<String> tools,
        @JsonProperty(required = true, value = "sessionInitMs") long sessionInitMs,
        @JsonProperty(required = true, value = "toolsRun") int toolsRun,
        @JsonProperty(required = true, value = "medianRoundtripMs") long medianRoundtripMs,
        @JsonProperty(required = true, value = "totalEstimatedPayloadTokens") int totalEstimatedPayloadTokens,
        @JsonProperty(required = true, value = "errorCount") int errorCount,
        @JsonProperty(required = true, value = "results") List<MetricsBenchResult> results) {
}