package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MetricsBenchResponse(
        @JsonProperty(required = true, value = "sessionInitMs") long sessionInitMs,
        @JsonProperty(required = true, value = "toolsRun") int toolsRun,
        @JsonProperty(required = true, value = "medianRoundtripMs") long medianRoundtripMs,
        @JsonProperty(required = true, value = "totalEstimatedPayloadTokens") int totalEstimatedPayloadTokens,
        @JsonProperty(required = true, value = "errorCount") int errorCount,
        @JsonProperty(required = true, value = "results") List<MetricsBenchResult> results,
        @JsonProperty(required = true, value = "recentRuns") List<MetricsBenchRun> recentRuns) {
}