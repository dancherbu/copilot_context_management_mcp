package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.OrchestrationBootstrap;
import dev.dancherbu.ccm.model.OrchestrationPlan;
import dev.dancherbu.ccm.model.ProjectReadinessReport;
import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class OrchestrationBootstrapTool {

    private final ProjectReadinessGuardService readinessGuardService;
    private final OrchestrationPlanTool orchestrationPlanTool;
    private final BuildContextBundleTool buildContextBundleTool;
    private final OrchestrationSessionService orchestrationSessionService;

    public OrchestrationBootstrapTool(
            ProjectReadinessGuardService readinessGuardService,
            OrchestrationPlanTool orchestrationPlanTool,
            BuildContextBundleTool buildContextBundleTool,
            OrchestrationSessionService orchestrationSessionService) {
        this.readinessGuardService = readinessGuardService;
        this.orchestrationPlanTool = orchestrationPlanTool;
        this.buildContextBundleTool = buildContextBundleTool;
        this.orchestrationSessionService = orchestrationSessionService;
    }

    @McpTool(
            name = "get_orchestration_bootstrap",
            description = "Return readiness, orchestration templates, and an initial context bundle in one deterministic call when a project is ready.")
    public OrchestrationBootstrap get_orchestration_bootstrap(
            @McpToolParam(description = "Natural language task, feature, or bug description for the initial context bundle", required = true)
                    String query,
            @McpToolParam(description = "Optional project override. If blank, use readiness defaults.", required = false)
                    String projectName,
            @McpToolParam(description = "Optional topK override for the initial context bundle", required = false)
                    Integer topK,
            @McpToolParam(description = "Optional maxFiles override for the initial context bundle", required = false)
                    Integer maxFiles,
            @McpToolParam(description = "Optional response mode override: lean or verbose", required = false)
                    String responseMode,
            @McpToolParam(description = "Optional token budget override", required = false)
                    Integer tokenBudget,
            @McpToolParam(description = "Optional context hash to short-circuit unchanged bundle payloads", required = false)
                    String ifNoneMatchContextHash,
                @McpToolParam(description = "Optional orchestration session id. If omitted, a new session is created and returned.", required = false)
                    String orchestrationSessionId,
                @McpToolParam(description = "When false and bundle is unchanged, omit initialContextBundle payload to reduce response size.", required = false)
                    Boolean returnPayloadOnUnchanged) {
        ProjectReadinessReport readiness = readinessGuardService.readiness(projectName);
        OrchestrationPlan plan = orchestrationPlanTool.get_orchestration_plan(projectName, tokenBudget);
            String sessionId = orchestrationSessionService.startOrResume(orchestrationSessionId);
            boolean includeUnchangedPayload = returnPayloadOnUnchanged == null || returnPayloadOnUnchanged;

        ContextBundle initialBundle = null;
        String initialContextBundleStatus;
        if (!readiness.hasReadyProject()) {
            initialContextBundleStatus = "No ready project yet. Resolve readinessBlockers and call again.";
        } else {
            String selectedProject = chooseProject(projectName, readiness, plan);
            int selectedTopK = topK == null || topK <= 0 ? plan.suggestedTopK() : topK;
            int selectedMaxFiles = maxFiles == null || maxFiles <= 0 ? plan.suggestedMaxFiles() : maxFiles;
            String selectedMode = normalizeMode(responseMode, plan.suggestedResponseMode());
            String expectedHash = resolveIfNoneMatchHash(ifNoneMatchContextHash, sessionId);
            orchestrationSessionService.rememberDefaults(sessionId, selectedProject, selectedMode, tokenBudget);

            try {
                initialBundle = buildContextBundleTool.build_context_bundle(
                        query,
                        selectedTopK,
                        selectedMaxFiles,
                        selectedProject,
                        selectedMode,
                        tokenBudget,
                        expectedHash);
                if (initialBundle != null) {
                    orchestrationSessionService.rememberContextHash(sessionId, "build_context_bundle", initialBundle.contextHash());
                }
                initialContextBundleStatus = initialBundle.unchanged()
                        ? "Initial bundle unchanged; reuse cached payload by contextHash."
                        : "Initial bundle generated.";
                if (initialBundle.unchanged() && !includeUnchangedPayload) {
                    String reusedHash = initialBundle.contextHash();
                    initialBundle = null;
                    initialContextBundleStatus = "Initial bundle unchanged; payload omitted (returnPayloadOnUnchanged=false). Reuse by contextHash="
                            + reusedHash;
                }
            } catch (RuntimeException ex) {
                initialContextBundleStatus = "Initial bundle unavailable: " + ex.getMessage();
            }
        }
        Map<String, Object> sessionState = orchestrationSessionService.snapshot(sessionId);

        return new OrchestrationBootstrap(
                Instant.now().toString(),
                query,
                sessionId,
                sessionState,
                readiness,
                plan,
                initialBundle,
                initialContextBundleStatus);
    }

    private String resolveIfNoneMatchHash(String ifNoneMatchContextHash, String sessionId) {
        if (ifNoneMatchContextHash != null && !ifNoneMatchContextHash.isBlank()) {
            return ifNoneMatchContextHash;
        }
        String remembered = orchestrationSessionService.knownContextHash(sessionId, "build_context_bundle");
        return remembered.isBlank() ? null : remembered;
    }

    private String chooseProject(String requestedProjectName, ProjectReadinessReport readiness, OrchestrationPlan plan) {
        if (requestedProjectName != null && !requestedProjectName.isBlank()) {
            return requestedProjectName.trim();
        }
        if (plan.suggestedProjectName() != null && !plan.suggestedProjectName().isBlank()) {
            return plan.suggestedProjectName();
        }
        if (readiness.suggestedProjectName() != null && !readiness.suggestedProjectName().isBlank()) {
            return readiness.suggestedProjectName();
        }
        if (!readiness.readyProjects().isEmpty()) {
            return readiness.readyProjects().getFirst();
        }
        return "";
    }

    private String normalizeMode(String requestedMode, String defaultMode) {
        String mode = requestedMode;
        if (mode == null || mode.isBlank()) {
            mode = defaultMode;
        }
        if (mode == null || mode.isBlank()) {
            return "lean";
        }
        return "lean".equals(mode.trim().toLowerCase(Locale.ROOT)) ? "lean" : "verbose";
    }
}