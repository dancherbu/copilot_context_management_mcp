package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.cache.SemanticCacheService;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.FileImpact;
import dev.dancherbu.ccm.model.FileImpactBatch;
import dev.dancherbu.ccm.model.ImpactAnalysisResult;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.support.ChecksumUtils;
import dev.dancherbu.ccm.support.TokenBudgetService;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeImpactedFilesTool {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzeImpactedFilesTool.class);

    private final CodebaseVectorStore codebaseVectorStore;
    private final SemanticCacheService semanticCacheService;
    private final ChatClient chatClient;
    private final TokenBudgetService tokenBudgetService;
    private final ObjectMapper objectMapper;
    private final CcmProperties properties;
    private final ProjectReadinessGuardService readinessGuardService;

    public AnalyzeImpactedFilesTool(
            CodebaseVectorStore codebaseVectorStore,
            SemanticCacheService semanticCacheService,
            ChatClient chatClient,
            TokenBudgetService tokenBudgetService,
            ObjectMapper objectMapper,
            CcmProperties properties,
            ProjectReadinessGuardService readinessGuardService) {
        this.codebaseVectorStore = codebaseVectorStore;
        this.semanticCacheService = semanticCacheService;
        this.chatClient = chatClient;
        this.tokenBudgetService = tokenBudgetService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.readinessGuardService = readinessGuardService;
    }

        @McpTool(
            name = "analyze_impacted_files",
            description = "Analyze the most likely impacted source files for a requested change and return file paths with reasons. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
        public ImpactAnalysisResult analyze_impacted_files(
            @McpToolParam(description = "Natural language description of the requested code change", required = true)
                    String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Project name used for readiness gating", required = true)
                    String projectName,
                @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                    String responseMode,
                @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                    Integer tokenBudget,
                @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                    String ifNoneMatchContextHash) {
        IndexCoverageProject project = readinessGuardService.requireReadyProject(projectName, "analyze_impacted_files");
            String normalizedMode = normalizeMode(responseMode);
            String scopedQuery = project.projectName() + "::" + query;
            List<FileImpact> cached = semanticCacheService.getImpacts(scopedQuery);
        if (cached != null) {
                List<FileImpact> cachedImpacts = "lean".equals(normalizedMode) ? compactImpacts(cached) : cached;
                int estimatedTokens = estimateTokens(query, cachedImpacts);
                String contextHash = contextHash(query, project.projectPath(), normalizedMode, cachedImpacts);
                if (matchesHash(ifNoneMatchContextHash, contextHash)) {
                    return unchangedResult(query, project.projectPath(), normalizedMode, contextHash, "semantic-cache");
                }
                return new ImpactAnalysisResult(
                        query,
                        project.projectPath(),
                        cachedImpacts,
                        normalizedMode,
                        estimatedTokens,
                        estimatedTokens,
                        suggestedTrimPlan(tokenBudget, estimatedTokens, topK == null ? 8 : Math.max(topK, 1), normalizedMode),
                        contextHash,
                        false,
                        true,
                        0,
                        "semantic-cache");
        }

        List<Document> matches = codebaseVectorStore.searchInProject(
                query,
                topK == null ? 8 : Math.max(topK, 1),
                project.projectPath());
        if (matches.isEmpty()) {
            String emptyHash = contextHash(query, project.projectPath(), normalizedMode, List.of());
            if (matchesHash(ifNoneMatchContextHash, emptyHash)) {
                return unchangedResult(query, project.projectPath(), normalizedMode, emptyHash, "live-index");
            }
            return new ImpactAnalysisResult(
                    query,
                    project.projectPath(),
                    List.of(),
                    normalizedMode,
                    1,
                    1,
                    List.of("No indexed matches were found for this query in the selected project."),
                    emptyHash,
                    false,
                    false,
                    0,
                    "live-index");
        }

        String prompt = buildPrompt(query, matches);
        List<FileImpact> impacts = callModelOrFallback(query, matches, prompt);
        List<FileImpact> bounded = enforceTokenLimit(impacts);
        List<FileImpact> normalizedImpacts = "lean".equals(normalizedMode) ? compactImpacts(bounded) : bounded;
        semanticCacheService.putImpacts(scopedQuery, bounded);
        int estimatedTokens = estimateTokens(query, normalizedImpacts);
        String contextHash = contextHash(query, project.projectPath(), normalizedMode, normalizedImpacts);
        if (matchesHash(ifNoneMatchContextHash, contextHash)) {
            return unchangedResult(query, project.projectPath(), normalizedMode, contextHash, "live-index");
        }
        return new ImpactAnalysisResult(
                query,
                project.projectPath(),
                normalizedImpacts,
                normalizedMode,
                estimatedTokens,
                estimatedTokens,
                suggestedTrimPlan(tokenBudget, estimatedTokens, topK == null ? 8 : Math.max(topK, 1), normalizedMode),
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

    private List<FileImpact> compactImpacts(List<FileImpact> impacts) {
        return impacts.stream()
                .limit(5)
                .map(impact -> new FileImpact(
                        impact.filePath(),
                        impact.reasonForEdit().length() <= 160
                                ? impact.reasonForEdit()
                                : impact.reasonForEdit().substring(0, 160) + "..."))
                .toList();
    }

    private int estimateTokens(String query, List<FileImpact> impacts) {
        String joined = impacts.stream()
                .map(impact -> impact.filePath() + "|" + impact.reasonForEdit())
                .reduce("", String::concat);
        return Math.max(1, tokenBudgetService.countTokens(query + joined));
    }

    private List<String> suggestedTrimPlan(Integer tokenBudget, int estimatedPromptTokens, int normalizedTopK, String responseMode) {
        if (tokenBudget == null || tokenBudget <= 0) {
            return "lean".equals(responseMode)
                    ? List.of("Lower topK to reduce candidate file spread if prompt pressure rises.")
                    : List.of("Use responseMode=lean to shorten reasons and cap returned impacts.", "Lower topK to reduce candidate file spread.");
        }
        if (estimatedPromptTokens <= tokenBudget) {
            return List.of("Estimated impact-analysis tokens are within budget.");
        }
        int suggestedTopK = Math.max(3, (int) Math.floor(normalizedTopK * 0.65));
        if ("lean".equals(responseMode)) {
            return List.of("Reduce topK from " + normalizedTopK + " to " + suggestedTopK + ".");
        }
        return List.of(
                "Set responseMode=lean before lowering retrieval limits.",
                "Reduce topK from " + normalizedTopK + " to " + suggestedTopK + ".");
    }

    private String contextHash(String query, String workspaceRoot, String responseMode, List<FileImpact> impacts) {
        String payload = query
                + "|"
                + workspaceRoot
                + "|"
                + responseMode
                + "|"
                + impacts.stream().map(impact -> impact.filePath() + impact.reasonForEdit()).reduce("", String::concat);
        return ChecksumUtils.sha256(payload);
    }

    private boolean matchesHash(String ifNoneMatchContextHash, String contextHash) {
        return ifNoneMatchContextHash != null
                && !ifNoneMatchContextHash.isBlank()
                && ifNoneMatchContextHash.trim().equalsIgnoreCase(contextHash);
    }

    private ImpactAnalysisResult unchangedResult(
            String query,
            String workspaceRoot,
            String responseMode,
            String contextHash,
            String sourceOfTruth) {
        return new ImpactAnalysisResult(
                query,
                workspaceRoot,
                List.of(),
                responseMode,
                1,
                1,
                List.of("Impact analysis unchanged; reuse cached payload and skip rehydration."),
                contextHash,
                true,
                true,
                0,
                sourceOfTruth + "-hash");
    }

    private List<FileImpact> callModelOrFallback(String query, List<Document> matches, String prompt) {
        BeanOutputConverter<FileImpactBatch> converter = new BeanOutputConverter<>(FileImpactBatch.class);
        try {
            String rawResponse = chatClient.prompt(prompt + System.lineSeparator() + converter.getFormat())
                    .call()
                    .content();
            FileImpactBatch batch = converter.convert(rawResponse);
            if (batch == null || batch.impacts() == null || batch.impacts().isEmpty()) {
                return heuristicImpacts(query, matches);
            }
            return deduplicate(batch.impacts());
        } catch (Exception ex) {
            logger.warn(
                    "Falling back to heuristic impact analysis for query because chat model '{}' was unavailable or returned an invalid response",
                    properties.getOllama().getChatModel(),
                    ex);
            return heuristicImpacts(query, matches);
        }
    }

    private String buildPrompt(String query, List<Document> matches) {
        StringBuilder builder = new StringBuilder();
        builder.append("Evaluate the following code chunks and identify which files are most likely impacted by the request. ")
                .append("Return only high-confidence edits. Request: ")
                .append(query)
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        int index = 1;
        for (Document match : matches) {
            builder.append("Chunk ").append(index++).append(':').append(System.lineSeparator())
                    .append("filePath: ").append(match.getMetadata().get("filePath")).append(System.lineSeparator())
                    .append("node_type: ").append(match.getMetadata().get("node_type")).append(System.lineSeparator())
                    .append("lineRange: ").append(match.getMetadata().get("startLine"))
                    .append('-')
                    .append(match.getMetadata().get("endLine"))
                    .append(System.lineSeparator())
                    .append("snippet:").append(System.lineSeparator())
                    .append(truncate(match.getText()))
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        return builder.toString();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int limit = properties.getMaxSnippetCharacters();
        return value.length() <= limit ? value : value.substring(0, limit) + System.lineSeparator() + "[truncated]";
    }

    // JTokkit is used here as a hard payload budget check so CPU-bound local model output does not
    // flood the MCP client with an oversized response.
    private List<FileImpact> enforceTokenLimit(List<FileImpact> impacts) {
        try {
            String payload = objectMapper.writeValueAsString(impacts);
            if (!tokenBudgetService.exceeds(payload, properties.getAnalysisTokenLimit())) {
                return impacts;
            }
        } catch (Exception ex) {
            logger.warn("Unable to evaluate token size for impact payload", ex);
            return impacts;
        }
        return fallbackSummary(impacts);
    }

    private List<FileImpact> fallbackSummary(List<FileImpact> impacts) {
        return impacts.stream()
                .limit(5)
                .map(impact -> new FileImpact(
                        impact.filePath(),
                        impact.reasonForEdit().length() > 160
                                ? impact.reasonForEdit().substring(0, 160) + "..."
                                : impact.reasonForEdit()))
                .toList();
    }

    private List<FileImpact> deduplicate(List<FileImpact> impacts) {
        Map<String, FileImpact> deduped = new LinkedHashMap<>();
        List<FileImpact> safeImpacts = impacts == null ? List.of() : impacts;
        for (FileImpact impact : safeImpacts) {
            deduped.putIfAbsent(impact.filePath(), impact);
        }
        return new ArrayList<>(deduped.values());
    }

    private List<FileImpact> heuristicImpacts(String query, List<Document> matches) {
        Map<String, HeuristicImpact> deduped = new LinkedHashMap<>();
        for (Document match : matches) {
            String filePath = metadata(match, "filePath");
            if (filePath.isBlank()) {
                continue;
            }
            deduped.computeIfAbsent(filePath, ignored -> new HeuristicImpact(filePath)).add(match);
        }
        return deduped.values().stream()
                .map(impact -> new FileImpact(impact.filePath, impact.reason(query)))
                .toList();
    }

    private String metadata(Document match, String key) {
        Object value = match.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private int metadataInt(Document match, String key) {
        Object value = match.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private final class HeuristicImpact {

        private final String filePath;
        private final LinkedHashSet<String> symbols = new LinkedHashSet<>();
        private final LinkedHashSet<String> nodeTypes = new LinkedHashSet<>();
        private int startLine = Integer.MAX_VALUE;
        private int endLine = 0;
        private int hitCount = 0;

        private HeuristicImpact(String filePath) {
            this.filePath = filePath;
        }

        private void add(Document match) {
            hitCount++;
            String symbol = Objects.toString(match.getMetadata().get("symbolName"), "").trim();
            if (!symbol.isBlank()) {
                symbols.add(symbol);
            }
            String nodeType = Objects.toString(match.getMetadata().get("node_type"), "").trim();
            if (!nodeType.isBlank()) {
                nodeTypes.add(nodeType);
            }
            int matchStart = metadataInt(match, "startLine");
            int matchEnd = metadataInt(match, "endLine");
            if (matchStart > 0) {
                startLine = Math.min(startLine, matchStart);
            }
            if (matchEnd > 0) {
                endLine = Math.max(endLine, matchEnd);
            }
        }

        private String reason(String query) {
            StringBuilder builder = new StringBuilder();
            builder.append("Vector match fallback for request '")
                    .append(query)
                    .append("'. ")
                    .append("Matched ")
                    .append(hitCount)
                    .append(hitCount == 1 ? " indexed chunk" : " indexed chunks");
            if (!symbols.isEmpty()) {
                builder.append(" around symbols ").append(String.join(", ", symbols));
            }
            if (!nodeTypes.isEmpty()) {
                builder.append(" in ").append(String.join(", ", nodeTypes));
            }
            if (startLine != Integer.MAX_VALUE && endLine > 0) {
                builder.append(" near lines ").append(startLine).append('-').append(endLine);
            }
            builder.append('.');
            return builder.toString();
        }
    }
}
