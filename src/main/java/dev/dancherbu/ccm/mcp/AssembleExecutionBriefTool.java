package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ChangeImpactTrace;
import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.ExecutionBrief;
import dev.dancherbu.ccm.model.TestObligations;
import dev.dancherbu.ccm.support.ChecksumUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class AssembleExecutionBriefTool {

    private final BuildContextBundleTool buildContextBundleTool;
    private final TraceChangeImpactTool traceChangeImpactTool;
    private final FindTestObligationsTool findTestObligationsTool;

    public AssembleExecutionBriefTool(
            BuildContextBundleTool buildContextBundleTool,
            TraceChangeImpactTool traceChangeImpactTool,
            FindTestObligationsTool findTestObligationsTool) {
        this.buildContextBundleTool = buildContextBundleTool;
        this.traceChangeImpactTool = traceChangeImpactTool;
        this.findTestObligationsTool = findTestObligationsTool;
    }

    @McpTool(
            name = "assemble_execution_brief",
            description = "Assemble a concise implementation brief for a coding model with files, risks, tests, constraints, and execution steps. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
    public ExecutionBrief assemble_execution_brief(
            @McpToolParam(description = "Natural language implementation task", required = true) String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Maximum number of files to include", required = false) Integer maxFiles,
            @McpToolParam(description = "Project name used for readiness gating", required = true)
                String projectName,
            @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                String responseMode,
            @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                Integer tokenBudget,
            @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                String ifNoneMatchContextHash) {
        ContextBundle bundle = buildContextBundleTool.build_context_bundle(query, topK, maxFiles, projectName, responseMode, tokenBudget, null);
        ChangeImpactTrace trace = traceChangeImpactTool.trace_change_impact(query, topK, maxFiles, projectName, responseMode, tokenBudget, null);
        TestObligations obligations = findTestObligationsTool.find_test_obligations(query, topK, maxFiles, projectName, responseMode, tokenBudget, null);
        boolean leanMode = "lean".equals(bundle.responseMode());

        List<String> filesToInspect = merge(bundle.likelyEntrypoints(), trace.likelyImpactedFiles(), bundle.supportingFiles());
        List<String> filesToEdit = trace.likelyImpactedFiles().stream().limit(6).toList();
        List<String> testsToUpdate = merge(obligations.existingTests(), obligations.missingTests());
        List<String> constraints = buildConstraints(trace, obligations);
        List<String> executionSteps = buildExecutionSteps(filesToInspect, filesToEdit, obligations);
        String handoffPrompt = buildHandoffPrompt(query, filesToInspect, filesToEdit, testsToUpdate, constraints, trace.riskSummary());
        if (leanMode) {
            filesToInspect = filesToInspect.stream().limit(6).toList();
            filesToEdit = filesToEdit.stream().limit(4).toList();
            testsToUpdate = testsToUpdate.stream().limit(5).toList();
            constraints = constraints.stream().limit(6).toList();
            executionSteps = executionSteps.stream().limit(5).toList();
            handoffPrompt = "Objective: " + query + " | Mode: lean | Focus files: " + String.join(", ", filesToEdit);
        }
        int estimatedTokens = (handoffPrompt.length() + String.join("", filesToEdit).length() + String.join("", testsToUpdate).length()) / 4;
        String contextHash = contextHash(
            query,
            bundle.responseMode(),
            filesToInspect,
            filesToEdit,
            testsToUpdate,
            constraints,
            executionSteps);
        if (matchesHash(ifNoneMatchContextHash, contextHash)) {
            return new ExecutionBrief(
                query,
                "Execution brief unchanged for this scope. Reuse previously cached payload by contextHash.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                1,
                bundle.responseMode(),
                1,
                List.of("Execution brief unchanged; reuse cached payload and skip rehydration."),
                contextHash,
                true,
                true,
                0,
                "context-hash");
        }
        List<String> suggestedTrimPlan = buildTrimPlan(tokenBudget, estimatedTokens, topK, maxFiles, leanMode);

        return new ExecutionBrief(
                query,
                "Implement the requested change with minimal repo-local context and explicit verification obligations.",
                bundle.likelyEntrypoints(),
                filesToInspect,
                filesToEdit,
                bundle.supportingFiles(),
                testsToUpdate,
                constraints,
                executionSteps,
                handoffPrompt,
                Math.max(1, estimatedTokens),
                bundle.responseMode(),
                Math.max(1, estimatedTokens),
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
                List<String> filesToInspect,
                List<String> filesToEdit,
                List<String> testsToUpdate,
                List<String> constraints,
                List<String> executionSteps) {
            return ChecksumUtils.sha256(
                query
                    + "|"
                    + responseMode
                    + "|"
                    + String.join(";", filesToInspect)
                    + "|"
                    + String.join(";", filesToEdit)
                    + "|"
                    + String.join(";", testsToUpdate)
                    + "|"
                    + String.join(";", constraints)
                    + "|"
                    + String.join(";", executionSteps));
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
                plan.add("Use responseMode=lean to reduce brief payload and handoff prompt size.");
            }
            plan.add("Lower topK and maxFiles to shrink impacted-file selection.");
            return plan;
        }
        if (estimatedPromptTokens <= tokenBudget) {
            plan.add("Estimated execution-brief tokens are within budget.");
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

    private List<String> buildConstraints(ChangeImpactTrace trace, TestObligations obligations) {
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        constraints.add("Keep changes scoped to the mounted repo and avoid broadening the watch root.");
        constraints.add("Preserve MCP transport compatibility and validate tools over /sse and /mcp/message.");
        constraints.add("Do not reintroduce secret-bearing files into the index or disable API-key protection.");
        if (!obligations.missingTests().isEmpty()) {
            constraints.add("Add or update focused tests for missing source/test pairings before considering the change complete.");
        }
        constraints.addAll(trace.riskSummary());
        return new ArrayList<>(constraints);
    }

    private List<String> buildExecutionSteps(
            List<String> filesToInspect, List<String> filesToEdit, TestObligations obligations) {
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        if (!filesToInspect.isEmpty()) {
            steps.add("Inspect these entrypoints and supporting files first: " + String.join(", ", filesToInspect.stream().limit(6).toList()));
        }
        if (!filesToEdit.isEmpty()) {
            steps.add("Edit the highest-signal implementation files next: " + String.join(", ", filesToEdit));
        }
        if (!obligations.existingTests().isEmpty() || !obligations.missingTests().isEmpty()) {
            steps.add("Update existing tests or add missing ones: " + String.join(", ", merge(obligations.existingTests(), obligations.missingTests())));
        }
        steps.addAll(obligations.suggestedScenarios().stream().limit(3).toList());
        steps.add("Validate with containerized Maven tests, Docker Compose health, and a live MCP tool call.");
        return new ArrayList<>(steps);
    }

    private String buildHandoffPrompt(
            String query,
            List<String> filesToInspect,
            List<String> filesToEdit,
            List<String> testsToUpdate,
            List<String> constraints,
            List<String> risks) {
        StringBuilder builder = new StringBuilder();
        builder.append("Objective: ").append(query).append(System.lineSeparator())
                .append("Inspect first: ").append(String.join(", ", filesToInspect)).append(System.lineSeparator())
                .append("Edit first: ").append(String.join(", ", filesToEdit)).append(System.lineSeparator())
                .append("Tests to update: ").append(String.join(", ", testsToUpdate)).append(System.lineSeparator())
                .append("Constraints: ").append(String.join(" | ", constraints)).append(System.lineSeparator())
                .append("Risks: ").append(String.join(" | ", risks));
        return builder.toString();
    }

    private List<String> merge(List<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) {
            merged.addAll(group);
        }
        return new ArrayList<>(merged);
    }
}