package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IndexCoverageNodeType(
        @JsonProperty(required = true, value = "nodeType") String nodeType,
        @JsonProperty(required = true, value = "total") int total,
        @JsonProperty(required = true, value = "embedded") int embedded,
        @JsonProperty(required = true, value = "coveragePercent") double coveragePercent) {
}