package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.GuidanceFile;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.model.ProjectGuidance;
import dev.dancherbu.ccm.support.ChecksumUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class ProjectGuidanceTool {

    private static final Pattern GUIDANCE_FILE_PATTERN = Pattern.compile(
            "(?i)(copilot-instructions\\.md|copilot-secrets\\.md|.*\\.instructions\\.md|.*\\.prompt\\.md|.*\\.agent\\.md|ag?ents\\.md|skill\\.md|readme\\.md)$");
        private static final Pattern BACKTICK_PATH_PATTERN = Pattern.compile("`([^`]+)`");
        private static final Pattern SECRET_PATH_PATTERN = Pattern.compile(
            "(?i)([A-Za-z0-9_./-]*secret[A-Za-z0-9_./-]*\\.(md|txt|yaml|yml|json|env)|\\.env(?:\\.[A-Za-z0-9_.-]+)?)");
        private static final Pattern EXPLICIT_SECRET_KEY_PATTERN = Pattern.compile(
            "(?i)^(secret[_-]?file|secrets[_-]?file|secret[_-]?path|secrets[_-]?path)\\s*[:=]\\s*(.+)$");

    private final IndexInsightsService indexInsightsService;
    private final CcmProperties properties;
    private final ToolPlanningSupportService toolPlanningSupportService;

    public ProjectGuidanceTool(
            IndexInsightsService indexInsightsService,
            CcmProperties properties,
            ToolPlanningSupportService toolPlanningSupportService) {
        this.indexInsightsService = indexInsightsService;
        this.properties = properties;
        this.toolPlanningSupportService = toolPlanningSupportService;
    }

    @McpTool(
            name = "get_project_guidance",
            description = "Discover project purpose, coding standards, deployment guidance, and secret locations from local project instruction files.")
    public ProjectGuidance get_project_guidance(
            @McpToolParam(description = "Optional project name. If omitted, the first discovered project is used.", required = false)
                    String projectName,
            @McpToolParam(description = "Response mode: verbose (default) or lean for compact output", required = false)
                    String responseMode,
            @McpToolParam(description = "Optional prompt-token budget target used to generate trim guidance", required = false)
                    Integer tokenBudget,
            @McpToolParam(description = "Optional hash from a prior guidance response. Matching hash returns compact unchanged payload.", required = false)
                    String ifNoneMatchGuidanceHash,
            @McpToolParam(description = "Include secret file content and secret references. Defaults to true for local workflows.", required = false)
                    Boolean includeSecrets) {
        String normalizedMode = normalizeMode(responseMode);
        boolean includeSecretContent = includeSecrets == null || includeSecrets;

        Selection selection = selectProject(projectName);
        List<Path> discoveredPaths = discoverGuidanceFiles(selection.projectRoot(), includeSecretContent);
        List<GuidanceFile> discoveredFiles = toGuidanceFiles(discoveredPaths, normalizedMode, includeSecretContent);

        List<String> bestPractices = extractSignals(discoveredPaths, List.of("best practice", "must", "should", "always", "avoid"), 10);
        List<String> codingStandards = extractSignals(discoveredPaths, List.of("coding standard", "style", "naming", "lint", "convention"), 10);
        List<String> deploymentInstructions = extractSignals(discoveredPaths, List.of("deploy", "deployment", "release", "restart", "docker compose", "kubernetes"), 10);
        List<String> secretLocations = extractSignals(discoveredPaths, List.of("secret", "secrets", ".env", "vault", "token", "password", "key"), 10);

        if (!includeSecretContent) {
            secretLocations = secretLocations.stream()
                    .filter(line -> !line.toLowerCase(Locale.ROOT).contains("copilot-secrets.md"))
                    .toList();
        }

        if ("lean".equals(normalizedMode)) {
            discoveredFiles = discoveredFiles.stream().limit(5).toList();
            bestPractices = bestPractices.stream().limit(4).toList();
            codingStandards = codingStandards.stream().limit(4).toList();
            deploymentInstructions = deploymentInstructions.stream().limit(4).toList();
            secretLocations = secretLocations.stream().limit(4).toList();
        }

        String guidanceReason = discoveredFiles.isEmpty()
                ? "No guidance files were discovered for this project root."
                : "Project guidance discovered from local instruction and documentation files.";

        String guidanceHash = guidanceHash(
                selection.projectName(),
                selection.projectRoot().toString(),
                normalizedMode,
                includeSecretContent,
                discoveredFiles,
                bestPractices,
                codingStandards,
                deploymentInstructions,
                secretLocations);

        if (matchesHash(ifNoneMatchGuidanceHash, guidanceHash)) {
            return new ProjectGuidance(
                    Instant.now().toString(),
                    selection.projectName(),
                    selection.projectRoot().toString(),
                    "Guidance unchanged for this project and mode.",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    normalizedMode,
                    1,
                    List.of("Guidance unchanged; reuse cached payload and skip rehydration."),
                    guidanceHash,
                    true,
                    true,
                    0,
                    "guidance-hash",
                    includeSecretContent,
                    includeSecretContent ? "high" : "normal");
        }

        int estimatedPromptTokens = estimateTokens(
                guidanceReason,
                discoveredFiles,
                bestPractices,
                codingStandards,
                deploymentInstructions,
                secretLocations);

        return new ProjectGuidance(
                Instant.now().toString(),
                selection.projectName(),
                selection.projectRoot().toString(),
                guidanceReason,
                discoveredFiles,
                bestPractices,
                codingStandards,
                deploymentInstructions,
                secretLocations,
                normalizedMode,
                estimatedPromptTokens,
                suggestedTrimPlan(tokenBudget, estimatedPromptTokens, normalizedMode, includeSecretContent),
                guidanceHash,
                false,
                false,
                0,
                "local-guidance-files",
                includeSecretContent,
                includeSecretContent ? "high" : "normal");
    }

    private Selection selectProject(String requestedProjectName) {
        List<IndexCoverageProject> projects = indexInsightsService.snapshot().projects();
        if (requestedProjectName != null && !requestedProjectName.isBlank()) {
            String requested = requestedProjectName.trim();
            for (IndexCoverageProject project : projects) {
                if (project.projectName().equalsIgnoreCase(requested)) {
                    return new Selection(project.projectName(), Path.of(project.projectPath()));
                }
            }
        }
        if (!projects.isEmpty()) {
            IndexCoverageProject first = projects.getFirst();
            return new Selection(first.projectName(), Path.of(first.projectPath()));
        }
        Path fallback = properties.getWatchRoot() == null ? Path.of(".") : properties.getWatchRoot();
        return new Selection(requestedProjectName == null ? "" : requestedProjectName.trim(), fallback);
    }

    private List<Path> discoverGuidanceFiles(Path projectRoot, boolean includeSecretContent) {
        LinkedHashSet<Path> discovered = new LinkedHashSet<>();
        Path instructionsPath = projectRoot.resolve("copilot-instructions.md");
        addIfFile(discovered, instructionsPath);
        if (includeSecretContent) {
            addIfFile(discovered, projectRoot.resolve("copilot-secrets.md"));
            for (Path referenced : referencedSecretFiles(projectRoot, instructionsPath)) {
                addIfFile(discovered, referenced);
            }
        }
        addIfFile(discovered, projectRoot.resolve("README.md"));
        addIfFile(discovered, projectRoot.resolve("AGENTS.md"));
        addIfFile(discovered, projectRoot.resolve(".vscode").resolve("copilot-instructions.md"));

        if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
            return new ArrayList<>(discovered);
        }

        try (Stream<Path> walk = Files.walk(projectRoot, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> GUIDANCE_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                    .filter(path -> includeSecretContent || !path.getFileName().toString().equalsIgnoreCase("copilot-secrets.md"))
                    .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                    .forEach(discovered::add);
        } catch (IOException ignored) {
            return new ArrayList<>(discovered);
        }
        return new ArrayList<>(discovered);
    }

    private List<Path> referencedSecretFiles(Path projectRoot, Path instructionsPath) {
        if (instructionsPath == null || !Files.exists(instructionsPath) || !Files.isRegularFile(instructionsPath)) {
            return List.of();
        }
        String content = read(instructionsPath);
        if (content.isBlank()) {
            return List.of();
        }
        LinkedHashSet<Path> referenced = new LinkedHashSet<>();
        List<String> candidates = new ArrayList<>();
        candidates.addAll(extractExplicitSecretPathCandidates(content));
        candidates.addAll(extractPathCandidates(content));
        for (String candidate : candidates) {
            if (candidate.isBlank()) {
                continue;
            }
            Path resolved = resolvePath(projectRoot, candidate.trim());
            if (resolved != null && Files.exists(resolved) && Files.isRegularFile(resolved)) {
                referenced.add(resolved);
            }
        }
        return new ArrayList<>(referenced);
    }

    private List<String> extractExplicitSecretPathCandidates(String content) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            String normalized = line.trim();
            if (normalized.isBlank() || normalized.startsWith("#")) {
                continue;
            }
            Matcher matcher = EXPLICIT_SECRET_KEY_PATTERN.matcher(normalized);
            if (!matcher.matches()) {
                continue;
            }
            String rawValue = matcher.group(2).trim();
            if (rawValue.isBlank()) {
                continue;
            }
            parseSecretPathValue(rawValue).forEach(candidates::add);
        }
        return new ArrayList<>(candidates);
    }

    private List<String> parseSecretPathValue(String rawValue) {
        String cleaned = rawValue;
        if ((cleaned.startsWith("[") && cleaned.endsWith("]")) || (cleaned.startsWith("(") && cleaned.endsWith(")"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        cleaned = cleaned.replace("\"", "").replace("'", "").trim();
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> parts = List.of(cleaned.split("[,;]"));
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (!token.isBlank()) {
                normalized.add(token);
            }
        }
        if (normalized.isEmpty()) {
            return List.of(cleaned);
        }
        return normalized;
    }

    private List<String> extractPathCandidates(String content) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Matcher backticks = BACKTICK_PATH_PATTERN.matcher(content);
        while (backticks.find()) {
            String token = backticks.group(1).trim();
            if (looksLikePath(token)) {
                candidates.add(token);
            }
        }
        Matcher secretPaths = SECRET_PATH_PATTERN.matcher(content);
        while (secretPaths.find()) {
            String token = secretPaths.group(1).trim();
            if (!token.isBlank()) {
                candidates.add(token);
            }
        }
        return new ArrayList<>(candidates);
    }

    private boolean looksLikePath(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        return normalized.contains("/")
                || normalized.contains("\\\\")
                || normalized.endsWith(".md")
                || normalized.endsWith(".txt")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".yml")
                || normalized.endsWith(".json")
                || normalized.equals(".env")
                || normalized.startsWith(".env.");
    }

    private Path resolvePath(Path projectRoot, String candidate) {
        try {
            Path raw = Path.of(candidate);
            return raw.isAbsolute() ? raw.normalize() : projectRoot.resolve(raw).normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private List<GuidanceFile> toGuidanceFiles(List<Path> files, String responseMode, boolean includeSecretContent) {
        List<GuidanceFile> result = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!includeSecretContent && fileName.equalsIgnoreCase("copilot-secrets.md")) {
                continue;
            }
            String content = read(file);
            String summary = summarize(content);
            String excerpt = excerpt(content, "lean".equals(responseMode) ? 240 : 900);
            result.add(new GuidanceFile(file.toString(), categorize(fileName), summary, excerpt));
        }
        return result;
    }

    private List<String> extractSignals(List<Path> files, List<String> keywords, int limit) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                continue;
            }
            for (String line : lines) {
                String normalized = line.trim();
                if (normalized.isBlank() || normalized.startsWith("#") || normalized.startsWith("---")) {
                    continue;
                }
                String lower = normalized.toLowerCase(Locale.ROOT);
                boolean matches = keywords.stream().anyMatch(lower::contains);
                if (matches) {
                    signals.add(normalized);
                }
                if (signals.size() >= limit) {
                    return signals.stream().limit(limit).toList();
                }
            }
        }
        return signals.stream().limit(limit).toList();
    }

    private String guidanceHash(
            String projectName,
            String projectPath,
            String responseMode,
            boolean includeSecrets,
            List<GuidanceFile> discoveredFiles,
            List<String> bestPractices,
            List<String> codingStandards,
            List<String> deploymentInstructions,
            List<String> secretLocations) {
        StringBuilder payload = new StringBuilder();
        payload.append(projectName)
                .append('|')
                .append(projectPath)
                .append('|')
                .append(responseMode)
                .append('|')
                .append(includeSecrets)
                .append('|');
        for (GuidanceFile file : discoveredFiles) {
            payload.append(file.filePath())
                    .append('|')
                    .append(file.category())
                    .append('|')
                    .append(file.summary())
                    .append('|')
                    .append(file.excerpt())
                    .append('|');
        }
        payload.append(String.join(";", bestPractices))
                .append('|')
                .append(String.join(";", codingStandards))
                .append('|')
                .append(String.join(";", deploymentInstructions))
                .append('|')
                .append(String.join(";", secretLocations));
        return ChecksumUtils.sha256(payload.toString());
    }

    private boolean matchesHash(String ifNoneMatchGuidanceHash, String guidanceHash) {
        return ifNoneMatchGuidanceHash != null
                && !ifNoneMatchGuidanceHash.isBlank()
                && ifNoneMatchGuidanceHash.trim().equalsIgnoreCase(guidanceHash);
    }

    private int estimateTokens(
            String guidanceReason,
            List<GuidanceFile> discoveredFiles,
            List<String> bestPractices,
            List<String> codingStandards,
            List<String> deploymentInstructions,
            List<String> secretLocations) {
        StringBuilder files = new StringBuilder();
        for (GuidanceFile file : discoveredFiles) {
            files.append(file.filePath())
                    .append('|')
                    .append(file.category())
                    .append('|')
                    .append(file.summary())
                    .append('|')
                    .append(file.excerpt())
                    .append('\n');
        }
        return toolPlanningSupportService.estimatePayloadTokens(
                guidanceReason,
                files.toString(),
                String.join("\n", bestPractices),
                String.join("\n", codingStandards),
                String.join("\n", deploymentInstructions),
                String.join("\n", secretLocations));
    }

    private List<String> suggestedTrimPlan(Integer tokenBudget, int estimatedPromptTokens, String responseMode, boolean includeSecrets) {
        List<String> plan = new ArrayList<>();
        if (tokenBudget == null || tokenBudget <= 0) {
            if (!"lean".equals(responseMode)) {
                plan.add("Use responseMode=lean to return shorter excerpts and fewer guidance bullets.");
            }
            if (includeSecrets) {
                plan.add("If secrets are not required for this call, set includeSecrets=false to reduce payload size.");
            }
            return plan;
        }
        if (estimatedPromptTokens <= tokenBudget) {
            return List.of("Estimated guidance tokens are within budget.");
        }
        if (!"lean".equals(responseMode)) {
            plan.add("Set responseMode=lean before retrying.");
        }
        if (includeSecrets) {
            plan.add("Set includeSecrets=false unless secret content is required for this task.");
        }
        plan.add("If still above budget, narrow guidance extraction to project-specific files only.");
        return plan;
    }

    private void addIfFile(LinkedHashSet<Path> target, Path path) {
        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
            target.add(path);
        }
    }

    private String normalizeMode(String responseMode) {
        if (responseMode == null || responseMode.isBlank()) {
            return "verbose";
        }
        return "lean".equals(responseMode.trim().toLowerCase(Locale.ROOT)) ? "lean" : "verbose";
    }

    private String categorize(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("secret")) {
            return "secrets";
        }
        if (lower.contains("instruction") || lower.contains("agent") || lower.contains("skill")) {
            return "instructions";
        }
        if (lower.contains("prompt")) {
            return "prompt";
        }
        if (lower.contains("readme")) {
            return "documentation";
        }
        return "guidance";
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "No readable content.";
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("---") || trimmed.startsWith("#")) {
                continue;
            }
            return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180) + "...";
        }
        return "Structured guidance file.";
    }

    private String excerpt(String content, int max) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String compact = content.strip();
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private record Selection(String projectName, Path projectRoot) {
    }
}