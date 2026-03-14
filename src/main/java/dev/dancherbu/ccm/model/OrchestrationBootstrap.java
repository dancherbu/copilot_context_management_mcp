package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record OrchestrationBootstrap(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "bootstrapQuery") String bootstrapQuery,
        @JsonProperty(required = true, value = "orchestrationSessionId") String orchestrationSessionId,
        @JsonProperty(required = true, value = "sessionState") Map<String, Object> sessionState,
        @JsonProperty(required = true, value = "projectReadiness") ProjectReadinessReport projectReadiness,
        @JsonProperty(required = true, value = "orchestrationPlan") OrchestrationPlan orchestrationPlan,
        @JsonProperty(required = true, value = "initialContextBundle") ContextBundle initialContextBundle,
        @JsonProperty(required = true, value = "initialContextBundleStatus") String initialContextBundleStatus) {
}