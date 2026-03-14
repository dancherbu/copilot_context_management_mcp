package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FileImpact(
        @JsonProperty(required = true, value = "filePath") String filePath,
        @JsonProperty(required = true, value = "reasonForEdit") String reasonForEdit) {
}
