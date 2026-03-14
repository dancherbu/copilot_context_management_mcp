package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record OrchestrationPlan(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "hasReadyProject") boolean hasReadyProject,
        @JsonProperty(required = true, value = "suggestedProjectName") String suggestedProjectName,
        @JsonProperty(required = true, value = "suggestedResponseMode") String suggestedResponseMode,
        @JsonProperty(required = true, value = "suggestedTopK") int suggestedTopK,
        @JsonProperty(required = true, value = "suggestedMaxFiles") int suggestedMaxFiles,
        @JsonProperty(required = true, value = "suggestedTokenBudget") int suggestedTokenBudget,
        @JsonProperty(required = true, value = "readinessBlockers") List<String> readinessBlockers,
        @JsonProperty(required = true, value = "toolArgumentTemplates") Map<String, Map<String, Object>> toolArgumentTemplates,
        @JsonProperty(required = true, value = "workflowSteps") List<String> workflowSteps) {
}
