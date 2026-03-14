package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.ContextFile;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.support.ChecksumUtils;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class BuildContextBundleTool {

    private static final List<String> ROOT_SUPPORTING_FILES = List.of(
            "README.md",
            "pom.xml",
            "docker-compose.yml",
            "Dockerfile",
            "src/main/resources/application.yml");

    private final CodebaseVectorStore codebaseVectorStore;
    private final CcmProperties properties;
    private final ProjectReadinessGuardService readinessGuardService;
    private final ToolPlanningSupportService toolPlanningSupportService;

    public BuildContextBundleTool(
            CodebaseVectorStore codebaseVectorStore,
            CcmProperties properties,
            ProjectReadinessGuardService readinessGuardService,
            ToolPlanningSupportService toolPlanningSupportService) {
        this.codebaseVectorStore = codebaseVectorStore;
        this.properties = properties;
        this.readinessGuardService = readinessGuardService;
        this.toolPlanningSupportService = toolPlanningSupportService;
    }

    @McpTool(
            name = "build_context_bundle",
            description = "Build a structured context bundle for a coding task using indexed repository chunks, likely entry points, and supporting project files. If projectName is provided, calls are blocked when that project is below coverage readiness threshold.")
    public ContextBundle build_context_bundle(
            @McpToolParam(description = "Natural language task, feature, or bug description", required = true)
                    String query,
            @McpToolParam(description = "Maximum number of vector hits to inspect", required = false) Integer topK,
            @McpToolParam(description = "Maximum number of files to include in the bundle", required = false)
                Integer maxFiles,
                @McpToolParam(description = "Project name used for readiness gating", required = true)
                String projectName,
            @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                String responseMode,
            @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                Integer tokenBudget,
            @McpToolParam(description = "Optional context hash from a prior response. Matching hash returns compact unchanged payload.", required = false)
                String ifNoneMatchContextHash) {
            IndexCoverageProject project = readinessGuardService.requireReadyProject(projectName, "build_context_bundle");
        String normalizedMode = normalizeMode(responseMode);
        boolean leanMode = "lean".equals(normalizedMode);
        int normalizedTopK = topK == null ? 10 : Math.max(topK, 1);
        int normalizedMaxFiles = maxFiles == null ? 6 : Math.max(maxFiles, 1);
        if (leanMode) {
            normalizedTopK = Math.min(normalizedTopK, 8);
            normalizedMaxFiles = Math.min(normalizedMaxFiles, 4);
        }

            List<Document> matches = codebaseVectorStore.searchInProject(query, normalizedTopK, project.projectPath());
        Map<String, BundleCandidate> byFile = aggregate(matches);
        List<ContextFile> rawFiles = byFile.values().stream()
                .sorted(Comparator.comparingInt(BundleCandidate::score).reversed())
                .limit(normalizedMaxFiles)
                .map(this::toContextFile)
                .toList();
        List<ContextFile> files = leanMode ? toLeanFiles(rawFiles) : rawFiles;

            List<String> knownFiles = codebaseVectorStore.getKnownFiles(project.projectPath());
        List<String> likelyEntrypoints = discoverLikelyEntrypoints(files, knownFiles, project.projectPath());
            List<String> supportingFiles = discoverSupportingFiles(knownFiles, likelyEntrypoints, project.projectPath());
        if (leanMode) {
            likelyEntrypoints = likelyEntrypoints.stream().limit(4).toList();
            supportingFiles = supportingFiles.stream().limit(4).toList();
        }
            String workspaceRoot = project.projectPath();
        String bundleReason = files.isEmpty()
                ? "No vector hits were found, so the bundle falls back to likely entry points and project scaffolding."
                : "Structured context bundle assembled deterministically from indexed vector hits and project scaffolding.";
        String contextHash = contextHash(query, workspaceRoot, normalizedMode, likelyEntrypoints, supportingFiles, files);
        if (matchesHash(ifNoneMatchContextHash, contextHash)) {
            return new ContextBundle(
                query,
                workspaceRoot,
                "Context unchanged for this scope. Reuse previously cached bundle by contextHash.",
                List.of(),
                List.of(),
                List.of(),
                normalizedMode,
                1,
                List.of("Bundle unchanged; reuse cached payload and skip rehydration."),
                contextHash,
                true,
                true,
                0,
                "context-hash");
        }
        int estimatedPromptTokens = estimateTokens(query, bundleReason, likelyEntrypoints, supportingFiles, files);
        List<String> suggestedTrimPlan = suggestedTrimPlan(tokenBudget, estimatedPromptTokens, normalizedTopK, normalizedMaxFiles, leanMode);

        return new ContextBundle(
                query,
                workspaceRoot,
                bundleReason,
                likelyEntrypoints,
                supportingFiles,
                files,
                normalizedMode,
                estimatedPromptTokens,
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
                String normalizedMode,
                List<String> likelyEntrypoints,
                List<String> supportingFiles,
                List<ContextFile> files) {
            StringBuilder payload = new StringBuilder();
            payload.append(query)
                .append('|')
                .append(workspaceRoot)
                .append('|')
                .append(normalizedMode)
                .append('|')
                .append(String.join(";", likelyEntrypoints))
                .append('|')
                .append(String.join(";", supportingFiles));
            for (ContextFile file : files) {
                payload.append('|')
                    .append(file.filePath())
                    .append('|')
                    .append(file.reason())
                    .append('|')
                    .append(file.lineRange())
                    .append('|')
                    .append(String.join(",", file.matchedSymbols()));
            }
            return ChecksumUtils.sha256(payload.toString());
            }

            private boolean matchesHash(String ifNoneMatchContextHash, String contextHash) {
            return ifNoneMatchContextHash != null
                && !ifNoneMatchContextHash.isBlank()
                && ifNoneMatchContextHash.trim().equalsIgnoreCase(contextHash);
            }

    private String normalizeMode(String responseMode) {
        if (responseMode == null || responseMode.isBlank()) {
            return "verbose";
        }
        String mode = responseMode.trim().toLowerCase(Locale.ROOT);
        return "lean".equals(mode) ? "lean" : "verbose";
    }

    private List<ContextFile> toLeanFiles(List<ContextFile> files) {
        return files.stream()
                .map(file -> new ContextFile(
                        file.filePath(),
                        compactReason(file.reason()),
                        file.matchedSymbols().stream().limit(3).toList(),
                        file.lineRange(),
                        ""))
                .toList();
    }

    private int estimateTokens(
            String query,
            String bundleReason,
            List<String> likelyEntrypoints,
            List<String> supportingFiles,
            List<ContextFile> files) {
        StringBuilder filesSection = new StringBuilder();
        for (ContextFile file : files) {
            filesSection
                    .append(file.filePath())
                    .append('|')
                    .append(file.reason())
                    .append('|')
                    .append(String.join(",", file.matchedSymbols()))
                    .append('|')
                    .append(file.lineRange())
                    .append('|')
                    .append(file.snippetPreview())
                    .append('\n');
        }
        return toolPlanningSupportService.estimatePayloadTokens(
                query,
                bundleReason,
                String.join("\n", likelyEntrypoints),
                String.join("\n", supportingFiles),
                filesSection.toString());
    }

    private List<String> suggestedTrimPlan(
            Integer tokenBudget,
            int estimatedPromptTokens,
            int normalizedTopK,
            int normalizedMaxFiles,
            boolean leanMode) {
        List<String> plan = new ArrayList<>();
        if (tokenBudget == null || tokenBudget <= 0) {
            if (!leanMode) {
                plan.add("Use responseMode=lean for compact bundles with fewer files and shorter snippets.");
            }
            plan.add("If prompt pressure appears high, lower topK (for example 10->6) before retrying.");
            plan.add("If context is still large, lower maxFiles (for example 6->4) to prioritize strongest matches.");
            return plan;
        }

        if (estimatedPromptTokens <= tokenBudget) {
            plan.add("Estimated bundle tokens are within budget.");
            return plan;
        }

        if (!leanMode) {
            plan.add("Set responseMode=lean to remove snippet previews and cap non-essential context lists.");
        }
        int suggestedTopK = Math.max(3, (int) Math.floor(normalizedTopK * 0.65));
        int suggestedMaxFiles = Math.max(2, (int) Math.floor(normalizedMaxFiles * 0.65));
        plan.add("Reduce topK from " + normalizedTopK + " to " + suggestedTopK + " and retry.");
        plan.add("Reduce maxFiles from " + normalizedMaxFiles + " to " + suggestedMaxFiles + " and retry.");
        plan.add("If still above budget, split the query into smaller scoped tasks per subsystem.");
        return plan;
    }

    private String compactReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Indexed match.";
        }
        int boundary = reason.indexOf('.');
        if (boundary > 0) {
            return reason.substring(0, boundary + 1);
        }
        return reason.length() <= 120 ? reason : reason.substring(0, 120) + "...";
    }

    private Map<String, BundleCandidate> aggregate(List<Document> matches) {
        Map<String, BundleCandidate> byFile = new LinkedHashMap<>();
        for (Document match : matches) {
            String filePath = metadata(match, "filePath");
            if (filePath.isBlank()) {
                continue;
            }
            byFile.computeIfAbsent(filePath, BundleCandidate::new).add(match);
        }
        return byFile;
    }

    private ContextFile toContextFile(BundleCandidate candidate) {
        return new ContextFile(
                candidate.filePath,
                candidate.reason(),
                new ArrayList<>(candidate.matchedSymbols),
                candidate.lineRange(),
                candidate.snippetPreview);
    }

    private List<String> discoverLikelyEntrypoints(List<ContextFile> files, List<String> knownFiles, String projectRoot) {
        LinkedHashSet<String> entrypoints = new LinkedHashSet<>();
        for (ContextFile file : files) {
            if (looksLikeEntrypoint(file.filePath())) {
                entrypoints.add(file.filePath());
            }
        }
        for (String filePath : knownFiles) {
            if (looksLikeEntrypoint(filePath)) {
                entrypoints.add(filePath);
            }
        }
        for (String filePath : resolveRootSupportingFiles(projectRoot)) {
            if (looksLikeEntrypoint(filePath)) {
                entrypoints.add(filePath);
            }
        }
        return entrypoints.stream().limit(6).toList();
    }

    private List<String> discoverSupportingFiles(List<String> knownFiles, List<String> likelyEntrypoints, String projectRoot) {
        LinkedHashSet<String> supporting = new LinkedHashSet<>();
        supporting.addAll(resolveRootSupportingFiles(projectRoot));
        supporting.addAll(knownFiles.stream()
                .filter(this::looksLikeSupportingFile)
                .toList());
        supporting.removeAll(likelyEntrypoints);
        return supporting.stream().limit(8).toList();
    }

    private List<String> resolveRootSupportingFiles(String projectRoot) {
        Path root = (projectRoot == null || projectRoot.isBlank())
                ? properties.getWatchRoot()
                : Path.of(projectRoot);
        if (root == null) {
            return List.of();
        }
        return ROOT_SUPPORTING_FILES.stream()
                .map(root::resolve)
                .filter(Files::exists)
                .map(Path::toString)
                .toList();
    }

    private boolean looksLikeEntrypoint(String filePath) {
        return filePath.endsWith("Application.java")
                || filePath.endsWith("ApplicationConfig.java")
                || filePath.endsWith("OperationalMcpProvider.java")
                || filePath.endsWith("docker-compose.yml")
                || filePath.endsWith("application.yml")
                || filePath.endsWith("pom.xml");
    }

    private boolean looksLikeSupportingFile(String filePath) {
        return filePath.endsWith("README.md")
                || filePath.endsWith("pom.xml")
                || filePath.endsWith("application.yml")
                || filePath.endsWith("docker-compose.yml")
                || filePath.endsWith("Dockerfile")
                || filePath.endsWith("Config.java")
                || filePath.endsWith("Properties.java");
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

    private static final class BundleCandidate {

        private final String filePath;
        private final LinkedHashSet<String> matchedSymbols = new LinkedHashSet<>();
        private final LinkedHashSet<String> nodeTypes = new LinkedHashSet<>();
        private String snippetPreview = "";
        private int startLine = Integer.MAX_VALUE;
        private int endLine = 0;
        private int hitCount = 0;

        private BundleCandidate(String filePath) {
            this.filePath = filePath;
        }

        private void add(Document match) {
            hitCount++;
            String symbol = Objects.toString(match.getMetadata().get("symbolName"), "").trim();
            if (!symbol.isBlank()) {
                matchedSymbols.add(symbol);
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
            if (snippetPreview.isBlank()) {
                snippetPreview = truncate(match.getText());
            }
        }

        private int score() {
            return hitCount * 10 + matchedSymbols.size() * 2 + nodeTypes.size();
        }

        private String lineRange() {
            if (startLine == Integer.MAX_VALUE || endLine <= 0) {
                return "unknown";
            }
            return startLine + "-" + endLine;
        }

        private String reason() {
            StringBuilder builder = new StringBuilder();
            builder.append("Matched ")
                    .append(hitCount)
                    .append(hitCount == 1 ? " indexed chunk" : " indexed chunks");
            if (!matchedSymbols.isEmpty()) {
                builder.append(" for symbols ").append(String.join(", ", matchedSymbols));
            }
            if (!nodeTypes.isEmpty()) {
                builder.append(" in ").append(String.join(", ", nodeTypes));
            }
            if (startLine != Integer.MAX_VALUE && endLine > 0) {
                builder.append(" around lines ").append(lineRange());
            }
            builder.append('.');
            return builder.toString();
        }

        private static String truncate(String text) {
            if (text == null || text.isBlank()) {
                return "";
            }
            String normalized = text.strip();
            return normalized.length() <= 320 ? normalized : normalized.substring(0, 320) + "...";
        }

        private static int metadataInt(Document match, String key) {
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
    }
}