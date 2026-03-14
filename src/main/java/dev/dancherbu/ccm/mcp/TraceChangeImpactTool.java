package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.ChangeImpactTrace;
import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.support.ChecksumUtils;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class TraceChangeImpactTool {

    private final BuildContextBundleTool buildContextBundleTool;
    private final ToolPlanningSupportService planningSupportService;
    private final CcmProperties properties;
        private final CodebaseVectorStore codebaseVectorStore;
        private final ProjectReadinessGuardService readinessGuardService;

    public TraceChangeImpactTool(
            BuildContextBundleTool buildContextBundleTool,
            ToolPlanningSupportService planningSupportService,
                        CcmProperties properties,
                        CodebaseVectorStore codebaseVectorStore,
                        ProjectReadinessGuardService readinessGuardService) {
        this.buildContextBundleTool = buildContextBundleTool;
        this.planningSupportService = planningSupportService;
        this.properties = properties;
                this.codebaseVectorStore = codebaseVectorStore;
                this.readinessGuardService = readinessGuardService;
    }

    @McpTool(
            name = "trace_change_impact",
            description = "Trace likely blast radius for a requested change, including configs, tests, operational touchpoints, and risk areas. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
    public ChangeImpactTrace trace_change_impact(
            @McpToolParam(description = "Natural language task, feature, bug, or refactor description", required = true)
                    String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Maximum number of files to include in the trace", required = false)
                    Integer maxFiles,
            @McpToolParam(description = "Project name used for readiness gating", required = true)
                    String projectName,
            @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                    String responseMode,
            @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                    Integer tokenBudget,
            @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                    String ifNoneMatchContextHash) {
        IndexCoverageProject project = readinessGuardService.requireReadyProject(projectName, "trace_change_impact");
        ContextBundle bundle = buildContextBundleTool.build_context_bundle(query, topK, maxFiles, projectName, responseMode, tokenBudget, null);
        boolean leanMode = "lean".equals(bundle.responseMode());
        List<String> sourceFiles = planningSupportService.candidateSourceFiles(bundle);
        List<String> relatedConfigs = planningSupportService.relatedConfigs(bundle, bundle.supportingFiles());
        List<String> knownFiles = codebaseVectorStore.getKnownFiles(project.projectPath());
        List<Document> allDocuments = codebaseVectorStore.getAllDocuments(project.projectPath());
        List<String> relatedTests = planningSupportService.relatedTests(sourceFiles, allDocuments, knownFiles);
        List<String> missingTests = planningSupportService.missingTests(sourceFiles, allDocuments, knownFiles);
        List<String> dependencyEdges = planningSupportService.dependencyEdges(sourceFiles, allDocuments);
        List<String> operationalTouchpoints = planningSupportService.operationalTouchpoints(bundle, sourceFiles);
        List<String> riskSummary = planningSupportService.riskSummary(sourceFiles, relatedConfigs, missingTests);
        if (leanMode) {
            sourceFiles = sourceFiles.stream().limit(6).toList();
            relatedConfigs = relatedConfigs.stream().limit(5).toList();
            relatedTests = relatedTests.stream().limit(6).toList();
            dependencyEdges = dependencyEdges.stream().limit(8).toList();
            operationalTouchpoints = operationalTouchpoints.stream().limit(5).toList();
            riskSummary = riskSummary.stream().limit(4).toList();
        }
        int estimatedTokens = planningSupportService.estimatePayloadTokens(
                query,
                String.join("", sourceFiles),
                String.join("", dependencyEdges),
                String.join("", riskSummary));
        String contextHash = contextHash(query, project.projectPath(), sourceFiles, dependencyEdges, riskSummary, bundle.responseMode());
        if (matchesHash(ifNoneMatchContextHash, contextHash)) {
            return new ChangeImpactTrace(
                    query,
                    project.projectPath(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("Trace unchanged for this scope. Reuse previously cached payload by contextHash."),
                    1,
                    bundle.responseMode(),
                    1,
                    List.of("Trace unchanged; reuse cached payload and skip rehydration."),
                    contextHash,
                    true,
                    true,
                    0,
                    "context-hash");
        }
        List<String> suggestedTrimPlan = buildTrimPlan(tokenBudget, estimatedTokens, topK, maxFiles, leanMode);

        return new ChangeImpactTrace(
                query,
                project.projectPath(),
                sourceFiles,
                bundle.files(),
                dependencyEdges,
                relatedConfigs,
                relatedTests,
                operationalTouchpoints,
                riskSummary,
                estimatedTokens,
                bundle.responseMode(),
                estimatedTokens,
                suggestedTrimPlan,
                contextHash,
                false,
                false,
                0,
                "live-index");
    }

    private String contextHash(
            String query,
            String workspaceRoot,
            List<String> sourceFiles,
            List<String> dependencyEdges,
            List<String> riskSummary,
            String responseMode) {
        return ChecksumUtils.sha256(
                query
                        + "|"
                        + workspaceRoot
                        + "|"
                        + responseMode
                        + "|"
                        + String.join(";", sourceFiles)
                        + "|"
                        + String.join(";", dependencyEdges)
                        + "|"
                        + String.join(";", riskSummary));
    }

    private boolean matchesHash(String ifNoneMatchContextHash, String contextHash) {
        return ifNoneMatchContextHash != null
                && !ifNoneMatchContextHash.isBlank()
                && ifNoneMatchContextHash.trim().equalsIgnoreCase(contextHash);
    }

    private List<String> buildTrimPlan(
            Integer tokenBudget,
            int estimatedPromptTokens,
            Integer topK,
            Integer maxFiles,
            boolean leanMode) {
        List<String> plan = new ArrayList<>();
        int normalizedTopK = topK == null ? 10 : Math.max(topK, 1);
        int normalizedMaxFiles = maxFiles == null ? 6 : Math.max(maxFiles, 1);
        if (tokenBudget == null || tokenBudget <= 0) {
            if (!leanMode) {
                plan.add("Use responseMode=lean to reduce context breadth and dependency edge volume.");
            }
            plan.add("Lower topK and maxFiles to prioritize the highest-signal impact paths.");
            return plan;
        }
        if (estimatedPromptTokens <= tokenBudget) {
            plan.add("Estimated trace tokens are within budget.");
            return plan;
        }
        if (!leanMode) {
            plan.add("Set responseMode=lean before lowering retrieval limits.");
        }
        int suggestedTopK = Math.max(3, (int) Math.floor(normalizedTopK * 0.65));
        int suggestedMaxFiles = Math.max(2, (int) Math.floor(normalizedMaxFiles * 0.65));
        plan.add("Reduce topK from " + normalizedTopK + " to " + suggestedTopK + ".");
        plan.add("Reduce maxFiles from " + normalizedMaxFiles + " to " + suggestedMaxFiles + ".");
        return plan;
    }
}