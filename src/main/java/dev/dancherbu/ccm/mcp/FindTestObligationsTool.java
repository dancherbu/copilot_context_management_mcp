package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.model.TestObligations;
import dev.dancherbu.ccm.support.ChecksumUtils;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class FindTestObligationsTool {

    private final BuildContextBundleTool buildContextBundleTool;
    private final ToolPlanningSupportService planningSupportService;
    private final CodebaseVectorStore codebaseVectorStore;
        private final ProjectReadinessGuardService readinessGuardService;

    public FindTestObligationsTool(
            BuildContextBundleTool buildContextBundleTool,
            ToolPlanningSupportService planningSupportService,
                        CodebaseVectorStore codebaseVectorStore,
                        ProjectReadinessGuardService readinessGuardService) {
        this.buildContextBundleTool = buildContextBundleTool;
        this.planningSupportService = planningSupportService;
        this.codebaseVectorStore = codebaseVectorStore;
                this.readinessGuardService = readinessGuardService;
    }

    @McpTool(
            name = "find_test_obligations",
            description = "Determine which tests should be updated or added for a requested change, including missing source-to-test pairings and validation scenarios. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
    public TestObligations find_test_obligations(
            @McpToolParam(description = "Natural language task, feature, bug, or refactor description", required = true)
                    String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Maximum number of files to inspect", required = false) Integer maxFiles,
            @McpToolParam(description = "Project name used for readiness gating", required = true)
                                        String projectName,
                        @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                                        String responseMode,
                        @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                                        Integer tokenBudget,
            @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                    String ifNoneMatchContextHash) {
        IndexCoverageProject project = readinessGuardService.requireReadyProject(projectName, "find_test_obligations");
                ContextBundle bundle = buildContextBundleTool.build_context_bundle(query, topK, maxFiles, projectName, responseMode, tokenBudget, null);
                boolean leanMode = "lean".equals(bundle.responseMode());
        List<String> sourceFiles = planningSupportService.candidateSourceFiles(bundle);
        List<String> knownFiles = codebaseVectorStore.getKnownFiles(project.projectPath());
        List<Document> allDocuments = codebaseVectorStore.getAllDocuments(project.projectPath());
        List<String> existingTests = planningSupportService.relatedTests(sourceFiles, allDocuments, knownFiles);
        List<String> missingTests = planningSupportService.missingTests(sourceFiles, allDocuments, knownFiles);
        List<String> suggestedScenarios = planningSupportService.suggestedScenarios(sourceFiles, missingTests);
                List<String> verificationCommands = planningSupportService.verificationCommands();
                if (leanMode) {
                        sourceFiles = sourceFiles.stream().limit(6).toList();
                        existingTests = existingTests.stream().limit(6).toList();
                        missingTests = missingTests.stream().limit(5).toList();
                        suggestedScenarios = suggestedScenarios.stream().limit(4).toList();
                        verificationCommands = verificationCommands.stream().limit(2).toList();
                }
        int estimatedTokens = planningSupportService.estimatePayloadTokens(
                query,
                String.join("", sourceFiles),
                String.join("", existingTests),
                String.join("", missingTests),
                String.join("", suggestedScenarios));
        String contextHash = contextHash(
                query,
                bundle.responseMode(),
                sourceFiles,
                existingTests,
                missingTests,
                suggestedScenarios);
        if (matchesHash(ifNoneMatchContextHash, contextHash)) {
            return new TestObligations(
                    query,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("Test obligations unchanged for this scope. Reuse previously cached payload by contextHash."),
                    List.of(),
                    1,
                    bundle.responseMode(),
                    1,
                    List.of("Test obligations unchanged; reuse cached payload and skip rehydration."),
                    contextHash,
                    true,
                    true,
                    0,
                    "context-hash");
        }
                List<String> suggestedTrimPlan = buildTrimPlan(tokenBudget, estimatedTokens, topK, maxFiles, leanMode);

        return new TestObligations(
                query,
                sourceFiles,
                existingTests,
                missingTests,
                suggestedScenarios,
                                verificationCommands,
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
            String responseMode,
            List<String> sourceFiles,
            List<String> existingTests,
            List<String> missingTests,
            List<String> suggestedScenarios) {
        return ChecksumUtils.sha256(
                query
                        + "|"
                        + responseMode
                        + "|"
                        + String.join(";", sourceFiles)
                        + "|"
                        + String.join(";", existingTests)
                        + "|"
                        + String.join(";", missingTests)
                        + "|"
                        + String.join(";", suggestedScenarios));
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
                                plan.add("Use responseMode=lean to reduce source/test list size.");
                        }
                        plan.add("Lower topK and maxFiles to focus on fewer source files.");
                        return plan;
                }
                if (estimatedPromptTokens <= tokenBudget) {
                        plan.add("Estimated test-obligation tokens are within budget.");
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