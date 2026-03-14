package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.ContextFile;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class ToolPlanningSupportService {

    public List<String> candidateSourceFiles(ContextBundle bundle) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (ContextFile file : bundle.files()) {
            if (!isTestFile(file.filePath())) {
                files.add(file.filePath());
            }
        }
        for (String entrypoint : bundle.likelyEntrypoints()) {
            if (!isTestFile(entrypoint)) {
                files.add(entrypoint);
            }
        }
        return new ArrayList<>(files);
    }

    public List<String> relatedConfigs(ContextBundle bundle, List<String> knownFiles) {
        LinkedHashSet<String> related = new LinkedHashSet<>();
        related.addAll(bundle.supportingFiles().stream().filter(this::isConfigLike).toList());
        related.addAll(bundle.likelyEntrypoints().stream().filter(this::isConfigLike).toList());
        related.addAll(knownFiles.stream().filter(this::isConfigLike).limit(8).toList());
        return new ArrayList<>(related);
    }

    public List<String> relatedTests(List<String> sourceFiles, List<String> knownFiles) {
        return relatedTests(sourceFiles, List.of(), knownFiles);
    }

    public List<String> relatedTests(List<String> sourceFiles, List<Document> documents, List<String> knownFiles) {
        Set<String> known = Set.copyOf(knownFiles);
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        Map<String, List<Document>> documentsByFile = documentsByFile(documents);
        List<Document> testDocuments = documents.stream().filter(doc -> isTestFile(filePath(doc))).toList();
        for (String sourceFile : sourceFiles) {
            if (isTestFile(sourceFile)) {
                tests.add(sourceFile);
                continue;
            }
            for (String candidate : deriveTestCandidates(sourceFile)) {
                if (known.contains(candidate)) {
                    tests.add(candidate);
                }
            }
            linkedTestsBySymbol(sourceFile, documentsByFile.getOrDefault(sourceFile, List.of()), testDocuments)
                    .forEach(tests::add);
        }
        return new ArrayList<>(tests);
    }

    public List<String> missingTests(List<String> sourceFiles, List<String> knownFiles) {
        return missingTests(sourceFiles, List.of(), knownFiles);
    }

    public List<String> missingTests(List<String> sourceFiles, List<Document> documents, List<String> knownFiles) {
        Set<String> known = Set.copyOf(knownFiles);
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        Map<String, List<Document>> documentsByFile = documentsByFile(documents);
        List<Document> testDocuments = documents.stream().filter(doc -> isTestFile(filePath(doc))).toList();
        for (String sourceFile : sourceFiles) {
            if (isTestFile(sourceFile) || !sourceFile.endsWith(".java") || !sourceFile.contains("/src/main/java/")) {
                continue;
            }
            List<String> candidates = deriveTestCandidates(sourceFile);
            boolean hasExisting = candidates.stream().anyMatch(known::contains)
                    || !linkedTestsBySymbol(sourceFile, documentsByFile.getOrDefault(sourceFile, List.of()), testDocuments)
                            .isEmpty();
            if (!hasExisting && !candidates.isEmpty()) {
                missing.add(candidates.getFirst());
            }
        }
        return new ArrayList<>(missing);
    }

    public List<String> deriveTestCandidates(String sourceFile) {
        if (!sourceFile.endsWith(".java") || !sourceFile.contains("/src/main/java/")) {
            return List.of();
        }
        Path path = Path.of(sourceFile);
        String fileName = path.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        String basePath = sourceFile.replace("/src/main/java/", "/src/test/java/");
        String primary = basePath.substring(0, basePath.length() - ".java".length()) + "Test.java";
        String integration = basePath.substring(0, basePath.length() - ".java".length()) + "IT.java";
        if (className.endsWith("Test") || className.endsWith("IT")) {
            return List.of();
        }
        return List.of(primary, integration);
    }

    public List<String> operationalTouchpoints(ContextBundle bundle, List<String> sourceFiles) {
        LinkedHashSet<String> touchpoints = new LinkedHashSet<>();
        touchpoints.addAll(bundle.likelyEntrypoints());
        touchpoints.addAll(sourceFiles.stream().filter(this::isOperationalTouchpoint).toList());
        return touchpoints.stream().limit(8).toList();
    }

    public List<String> dependencyEdges(List<String> sourceFiles, List<Document> documents) {
        Map<String, List<Document>> documentsByFile = documentsByFile(documents);
        Map<String, String> symbolOwners = new LinkedHashMap<>();
        for (Document document : documents) {
            String ownerFile = filePath(document);
            if (ownerFile.isBlank() || isTestFile(ownerFile)) {
                continue;
            }
            String symbol = normalizedSymbol(document);
            if (!symbol.isBlank()) {
                symbolOwners.putIfAbsent(symbol, ownerFile);
            }
        }

        LinkedHashSet<String> edges = new LinkedHashSet<>();
        for (String sourceFile : sourceFiles) {
            for (Document document : documentsByFile.getOrDefault(sourceFile, List.of())) {
                String text = Objects.toString(document.getText(), "");
                if (text.isBlank()) {
                    continue;
                }
                for (Map.Entry<String, String> entry : symbolOwners.entrySet()) {
                    String symbol = entry.getKey();
                    String targetFile = entry.getValue();
                    if (sourceFile.equals(targetFile) || !containsSymbolReference(text, symbol)) {
                        continue;
                    }
                    edges.add(sourceFile + " -> " + targetFile + " via symbol " + symbol);
                }
            }
        }
        return edges.stream().limit(12).toList();
    }

    public int estimatePayloadTokens(String... sections) {
        int characters = 0;
        for (String section : sections) {
            characters += section == null ? 0 : section.length();
        }
        return Math.max(1, characters / 4);
    }

    public String normalizedSymbol(Document document) {
        String symbol = Objects.toString(document.getMetadata().get("symbolName"), "").trim();
        if (!symbol.isBlank() && !"(identifier)".equals(symbol)) {
            return symbol;
        }
        String filePath = filePath(document);
        if (filePath.endsWith(".java")) {
            String fileName = Path.of(filePath).getFileName().toString();
            return fileName.substring(0, fileName.length() - ".java".length());
        }
        return Path.of(filePath).getFileName().toString();
    }

    public String filePath(Document document) {
        return Objects.toString(document.getMetadata().get("filePath"), "");
    }

    public String lineRange(Document document) {
        Object start = document.getMetadata().get("startLine");
        Object end = document.getMetadata().get("endLine");
        return Objects.toString(start, "?") + "-" + Objects.toString(end, "?");
    }

    public String snippetPreview(Document document) {
        String text = Objects.toString(document.getText(), "").strip();
        return text.length() <= 280 ? text : text.substring(0, 280) + "...";
    }

    private List<String> linkedTestsBySymbol(String sourceFile, List<Document> sourceDocs, List<Document> testDocuments) {
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        symbols.add(baseClassName(sourceFile));
        sourceDocs.stream().map(this::normalizedSymbol).filter(symbol -> !symbol.isBlank()).forEach(symbols::add);
        for (Document testDocument : testDocuments) {
            String testText = Objects.toString(testDocument.getText(), "");
            if (symbols.stream().anyMatch(symbol -> containsSymbolReference(testText, symbol))) {
                tests.add(filePath(testDocument));
            }
        }
        return new ArrayList<>(tests);
    }

    private Map<String, List<Document>> documentsByFile(List<Document> documents) {
        Map<String, List<Document>> byFile = new LinkedHashMap<>();
        for (Document document : documents) {
            String filePath = filePath(document);
            if (filePath.isBlank()) {
                continue;
            }
            byFile.computeIfAbsent(filePath, ignored -> new ArrayList<>()).add(document);
        }
        byFile.values().forEach(list -> list.sort(Comparator.comparing(this::lineRange)));
        return byFile;
    }

    private String baseClassName(String sourceFile) {
        if (!sourceFile.endsWith(".java")) {
            return Path.of(sourceFile).getFileName().toString();
        }
        String fileName = Path.of(sourceFile).getFileName().toString();
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private boolean containsSymbolReference(String text, String symbol) {
        return !symbol.isBlank() && text.matches("(?s).*(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(symbol) + "(?![A-Za-z0-9_]).*");
    }

    public List<String> riskSummary(List<String> sourceFiles, List<String> relatedConfigs, List<String> missingTests) {
        LinkedHashSet<String> risks = new LinkedHashSet<>();
        if (sourceFiles.stream().anyMatch(path -> path.contains("/mcp/") || path.endsWith("McpApiKeyWebFilter.java"))) {
            risks.add("MCP contract or transport behavior may change; validate tools/list and live SSE calls.");
        }
        if (sourceFiles.stream().anyMatch(path -> path.contains("/watch/") || path.contains("/index/") || path.contains("/vector/"))) {
            risks.add("Index freshness or retrieval relevance may shift; verify vector-store updates and watched file behavior.");
        }
        if (!relatedConfigs.isEmpty()) {
            risks.add("Configuration or container wiring may be affected; validate Docker Compose and actuator liveness.");
        }
        if (sourceFiles.stream().anyMatch(path -> path.contains("Security") || path.contains("ApiKey") || path.contains("secret") || path.contains("auth"))) {
            risks.add("Security-sensitive behavior is in scope; re-check authentication and secret-ingest protections.");
        }
        if (!missingTests.isEmpty()) {
            risks.add("Some impacted source files do not have colocated tests; add focused coverage before trusting the change.");
        }
        if (risks.isEmpty()) {
            risks.add("No major operational hotspots were detected, but validate the affected MCP tool responses and repository indexing path.");
        }
        return new ArrayList<>(risks);
    }

    public List<String> suggestedScenarios(List<String> sourceFiles, List<String> missingTests) {
        LinkedHashSet<String> scenarios = new LinkedHashSet<>();
        if (sourceFiles.stream().anyMatch(path -> path.contains("/mcp/"))) {
            scenarios.add("Verify the tool is listed in tools/list and can be called through /sse and /mcp/message.");
        }
        if (sourceFiles.stream().anyMatch(path -> path.contains("/config/") || path.endsWith("application.yml") || path.endsWith("docker-compose.yml"))) {
            scenarios.add("Validate Spring Boot startup, Docker Compose health, and environment-driven configuration behavior.");
        }
        if (sourceFiles.stream().anyMatch(path -> path.contains("/watch/") || path.contains("/index/") || path.contains("/vector/"))) {
            scenarios.add("Reindex the watched repo and confirm stale vector entries are pruned while repo-local files remain searchable.");
        }
        if (sourceFiles.stream().anyMatch(path -> path.contains("Security") || path.contains("ApiKey") || path.contains("auth"))) {
            scenarios.add("Check anonymous versus authenticated access for /sse and /mcp/message.");
        }
        if (!missingTests.isEmpty()) {
            scenarios.add("Add or update targeted tests for missing source/test pairings before shipping the change.");
        }
        if (scenarios.isEmpty()) {
            scenarios.add("Add focused unit coverage for the deterministic output shape and one integration smoke test over MCP SSE.");
        }
        return new ArrayList<>(scenarios);
    }

    public List<String> verificationCommands() {
        return List.of(
                "docker run --rm -v \"$PWD\":/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test",
                "docker compose up -d --build app",
                "curl -H 'X-CCM-API-Key: <key>' http://ccm-mcp.local/sse");
    }

    private boolean isConfigLike(String path) {
        return path.endsWith("application.yml")
                || path.endsWith("pom.xml")
                || path.endsWith("docker-compose.yml")
                || path.endsWith("Dockerfile")
                || path.endsWith("Config.java")
                || path.endsWith("Properties.java");
    }

    private boolean isOperationalTouchpoint(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("application")
                || lower.contains("config")
                || lower.contains("provider")
                || lower.endsWith("docker-compose.yml")
                || lower.endsWith("application.yml")
                || lower.endsWith("pom.xml");
    }

    private boolean isTestFile(String path) {
        return path.contains("/src/test/") || path.endsWith("Test.java") || path.endsWith("IT.java");
    }
}