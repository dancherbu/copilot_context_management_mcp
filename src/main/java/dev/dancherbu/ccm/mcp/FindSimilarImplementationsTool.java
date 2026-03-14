package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.model.SimilarImplementation;
import dev.dancherbu.ccm.model.SimilarImplementations;
import dev.dancherbu.ccm.support.ChecksumUtils;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class FindSimilarImplementationsTool {

    private final CodebaseVectorStore codebaseVectorStore;
    private final ToolPlanningSupportService planningSupportService;
    private final CcmProperties properties;
        private final ProjectReadinessGuardService readinessGuardService;

    public FindSimilarImplementationsTool(
            CodebaseVectorStore codebaseVectorStore,
            ToolPlanningSupportService planningSupportService,
                        CcmProperties properties,
                        ProjectReadinessGuardService readinessGuardService) {
        this.codebaseVectorStore = codebaseVectorStore;
        this.planningSupportService = planningSupportService;
        this.properties = properties;
                this.readinessGuardService = readinessGuardService;
    }

    @McpTool(
            name = "find_similar_implementations",
            description = "Find similar implementation patterns in the indexed repository so coding models can reuse existing local approaches. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
    public SimilarImplementations find_similar_implementations(
            @McpToolParam(description = "Natural language description of the pattern or feature to find", required = true)
                    String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Maximum number of examples to return", required = false) Integer maxExamples,
            @McpToolParam(description = "Project name used for readiness gating", required = true)
                    String projectName,
            @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                    String responseMode,
            @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                    Integer tokenBudget,
            @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                    String ifNoneMatchContextHash) {
        IndexCoverageProject project = readinessGuardService.requireReadyProject(projectName, "find_similar_implementations");
        String normalizedMode = normalizeMode(responseMode);
        List<Document> matches = codebaseVectorStore.searchInProject(
                query,
                topK == null ? 10 : Math.max(topK, 1),
                project.projectPath());
        Map<String, SimilarImplementation> deduped = new LinkedHashMap<>();
        for (Document match : matches) {
            String filePath = planningSupportService.filePath(match);
            String symbol = planningSupportService.normalizedSymbol(match);
            String key = filePath + "::" + symbol;
            deduped.putIfAbsent(
                    key,
                    new SimilarImplementation(
                            filePath,
                            symbol,
                            planningSupportService.lineRange(match),
                            "Semantic vector hit for requested pattern; reuse this local implementation before inventing a new one.",
                            planningSupportService.snippetPreview(match)));
        }
        List<SimilarImplementation> examples = deduped.values().stream()
                .limit(maxExamples == null ? 6 : Math.max(maxExamples, 1))
                .toList();
                if ("lean".equals(normalizedMode)) {
                        examples = examples.stream()
                                        .limit(4)
                                        .map(example -> new SimilarImplementation(
                                                        example.filePath(),
                                                        example.symbolName(),
                                                        example.lineRange(),
                                                    example.reason(),
                                                        ""))
                                        .toList();
                }
        int estimatedTokens = planningSupportService.estimatePayloadTokens(
                query,
                examples.stream().map(example -> example.filePath() + example.symbolName() + example.snippetPreview()).reduce("", String::concat));
                String contextHash = contextHash(query, project.projectPath(), normalizedMode, examples);
                if (matchesHash(ifNoneMatchContextHash, contextHash)) {
                        return new SimilarImplementations(
                                        query,
                                        project.projectPath(),
                                        List.of(),
                                        1,
                                        normalizedMode,
                                        1,
                                        List.of("Similar-implementation set unchanged; reuse cached payload and skip rehydration."),
                                        contextHash,
                                        true,
                                        true,
                                        0,
                                        "context-hash");
                }
                List<String> trimPlan = suggestedTrimPlan(tokenBudget, estimatedTokens, topK == null ? 10 : Math.max(topK, 1), maxExamples == null ? 6 : Math.max(maxExamples, 1), normalizedMode);
        return new SimilarImplementations(
                query,
                project.projectPath(),
                examples,
                                estimatedTokens,
                                normalizedMode,
                                estimatedTokens,
                                trimPlan,
                                contextHash,
                                false,
                                false,
                                0,
                                "live-index");
    }

        private String normalizeMode(String responseMode) {
                if (responseMode == null || responseMode.isBlank()) {
                        return "verbose";
                }
                String mode = responseMode.trim().toLowerCase(Locale.ROOT);
                return "lean".equals(mode) ? "lean" : "verbose";
        }

        private List<String> suggestedTrimPlan(
                        Integer tokenBudget,
                        int estimatedPromptTokens,
                        int normalizedTopK,
                        int normalizedMaxExamples,
                        String responseMode) {
                List<String> plan = new ArrayList<>();
                if (tokenBudget == null || tokenBudget <= 0) {
                        if (!"lean".equals(responseMode)) {
                                plan.add("Use responseMode=lean to reduce snippet payload and example count.");
                        }
                        plan.add("Lower topK or maxExamples to keep only highest-signal reuse candidates.");
                        return plan;
                }
                if (estimatedPromptTokens <= tokenBudget) {
                        plan.add("Estimated similar-implementation tokens are within budget.");
                        return plan;
                }
                if (!"lean".equals(responseMode)) {
                        plan.add("Set responseMode=lean before lowering retrieval limits.");
                }
                int suggestedTopK = Math.max(3, (int) Math.floor(normalizedTopK * 0.65));
                int suggestedExamples = Math.max(2, (int) Math.floor(normalizedMaxExamples * 0.65));
                plan.add("Reduce topK from " + normalizedTopK + " to " + suggestedTopK + ".");
                plan.add("Reduce maxExamples from " + normalizedMaxExamples + " to " + suggestedExamples + ".");
                return plan;
        }

        private String contextHash(String query, String workspaceRoot, String responseMode, List<SimilarImplementation> examples) {
                String payload = query
                                + "|"
                                + workspaceRoot
                                + "|"
                                + responseMode
                                + "|"
                                + examples.stream()
                                                .map(example -> example.filePath() + example.symbolName() + example.lineRange())
                                                .reduce("", String::concat);
                return ChecksumUtils.sha256(payload);
        }

        private boolean matchesHash(String ifNoneMatchContextHash, String contextHash) {
                return ifNoneMatchContextHash != null
                                && !ifNoneMatchContextHash.isBlank()
                                && ifNoneMatchContextHash.trim().equalsIgnoreCase(contextHash);
        }
}