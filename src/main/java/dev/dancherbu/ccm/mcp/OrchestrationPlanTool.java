package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.OrchestrationPlan;
import dev.dancherbu.ccm.model.ProjectReadinessReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class OrchestrationPlanTool {

    private static final int DEFAULT_TOKEN_BUDGET = 1800;

    private final ProjectReadinessGuardService readinessGuardService;

    public OrchestrationPlanTool(ProjectReadinessGuardService readinessGuardService) {
        this.readinessGuardService = readinessGuardService;
    }

    @McpTool(
            name = "get_orchestration_plan",
            description = "Return a single-call orchestration plan with project readiness defaults and deterministic argument templates for core MCP tools.")
    public OrchestrationPlan get_orchestration_plan(
            @McpToolParam(description = "Optional project override. If blank, use readiness defaults.", required = false)
                    String projectName,
            @McpToolParam(description = "Optional token budget override for generated templates.", required = false)
                    Integer tokenBudget) {
        ProjectReadinessReport readiness = readinessGuardService.readiness(projectName);

        String selectedProject = chooseProject(projectName, readiness);
        int selectedTokenBudget = tokenBudget == null || tokenBudget <= 0 ? DEFAULT_TOKEN_BUDGET : tokenBudget;

        Map<String, Map<String, Object>> templates = buildTemplates(
                selectedProject,
                readiness.suggestedResponseMode(),
                readiness.suggestedTopK(),
                readiness.suggestedMaxFiles(),
                selectedTokenBudget,
                readiness.hasReadyProject());

        return new OrchestrationPlan(
                Instant.now().toString(),
                readiness.hasReadyProject(),
                selectedProject,
                readiness.suggestedResponseMode(),
                readiness.suggestedTopK(),
                readiness.suggestedMaxFiles(),
                selectedTokenBudget,
                readiness.readinessBlockers(),
                templates,
                workflowSteps(readiness.hasReadyProject()));
    }

    private String chooseProject(String requestedProjectName, ProjectReadinessReport readiness) {
        if (requestedProjectName != null && !requestedProjectName.isBlank()) {
            return requestedProjectName.trim();
        }
        if (readiness.suggestedProjectName() != null && !readiness.suggestedProjectName().isBlank()) {
            return readiness.suggestedProjectName();
        }
        if (!readiness.readyProjects().isEmpty()) {
            return readiness.readyProjects().getFirst();
        }
        return "";
    }

    private Map<String, Map<String, Object>> buildTemplates(
            String projectName,
            String responseMode,
            int topK,
            int maxFiles,
            int tokenBudget,
            boolean hasReadyProject) {
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        String effectiveProject = projectName == null || projectName.isBlank()
                ? "<ready-project-name>"
                : projectName;

        Map<String, Object> readinessArgs = new LinkedHashMap<>();
        readinessArgs.put("projectName", hasReadyProject ? effectiveProject : "");
        templates.put("get_project_readiness", readinessArgs);

        templates.put("analyze_impacted_files", mapOf(
                "query", "<your-change-request>",
                "topK", topK,
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        templates.put("build_context_bundle", mapOf(
                "query", "<your-change-request>",
                "topK", topK,
                "maxFiles", maxFiles,
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        templates.put("trace_change_impact", mapOf(
                "query", "<your-change-request>",
                "topK", topK,
                "maxFiles", maxFiles,
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        templates.put("find_test_obligations", mapOf(
                "query", "<your-change-request>",
                "topK", topK,
                "maxFiles", maxFiles,
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        templates.put("assemble_execution_brief", mapOf(
                "query", "<your-change-request>",
                "topK", topK,
                "maxFiles", maxFiles,
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        templates.put("find_similar_implementations", mapOf(
                "query", "<your-pattern-or-feature>",
                "topK", topK,
                "maxExamples", Math.max(2, maxFiles),
                "projectName", effectiveProject,
                "responseMode", responseMode,
                "tokenBudget", tokenBudget,
                "ifNoneMatchContextHash", "<prior-context-hash-optional>"));

        return templates;
    }

    private List<String> workflowSteps(boolean hasReadyProject) {
        if (!hasReadyProject) {
            return List.of(
                    "Call get_project_readiness until hasReadyProject=true.",
                    "When ready, call get_orchestration_plan again to refresh templates.");
        }
        return List.of(
                "Start with analyze_impacted_files or build_context_bundle template.",
                "Store contextHash from each response and pass it back as ifNoneMatchContextHash on repeat calls.",
                "Use assemble_execution_brief template for coding handoff after impact and tests are clear.");
    }

    private Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
