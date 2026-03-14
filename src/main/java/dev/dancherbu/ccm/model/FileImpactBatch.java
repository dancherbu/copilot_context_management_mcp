package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FileImpactBatch(
        @JsonProperty(required = true, value = "impacts") List<FileImpact> impacts) {
}
