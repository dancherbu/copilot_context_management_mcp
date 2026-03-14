package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.chunking.TreeSitterDocumentSplitter;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.index.ReindexProgressService;
import dev.dancherbu.ccm.index.ProjectIndexabilityService;
import dev.dancherbu.ccm.model.CodeChunk;
import dev.dancherbu.ccm.model.IndexCoverageFile;
import dev.dancherbu.ccm.model.IndexCoverageNodeType;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.model.IndexCoverageSnapshot;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import dev.dancherbu.ccm.watch.GitIgnoreMatcherService;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

@Service
public class IndexInsightsService {

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);
    private static final Duration INITIAL_WAIT = Duration.ofSeconds(3);
    private static final double COVERAGE_EPSILON = 0.0001;

    private final CcmProperties properties;
    private final GitIgnoreMatcherService gitIgnoreMatcherService;
    private final ProjectIndexabilityService projectIndexabilityService;
    private final TreeSitterDocumentSplitter documentSplitter;
    private final CodebaseVectorStore vectorStore;
    private final ReindexProgressService reindexProgressService;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    private volatile IndexCoverageSnapshot cachedSnapshot;
    private volatile Instant lastSnapshotAt;
    private volatile CompletableFuture<IndexCoverageSnapshot> refreshFuture;

    public IndexInsightsService(
            CcmProperties properties,
            GitIgnoreMatcherService gitIgnoreMatcherService,
            ProjectIndexabilityService projectIndexabilityService,
            TreeSitterDocumentSplitter documentSplitter,
            CodebaseVectorStore vectorStore,
            ReindexProgressService reindexProgressService) {
        this.properties = properties;
        this.gitIgnoreMatcherService = gitIgnoreMatcherService;
        this.projectIndexabilityService = projectIndexabilityService;
        this.documentSplitter = documentSplitter;
        this.vectorStore = vectorStore;
        this.reindexProgressService = reindexProgressService;
    }

    @PostConstruct
    void warmSnapshot() {
        triggerRefresh(false);
    }

    public IndexCoverageSnapshot snapshot() {
        IndexCoverageSnapshot snapshot = cachedSnapshot;
        if (snapshot != null) {
            ReindexProgressService.ReindexProgress progress = reindexProgressService.snapshot();
            if (progressChanged(snapshot, progress)) {
                IndexCoverageSnapshot liveSnapshot = withProgress(snapshot, progress);
                cachedSnapshot = liveSnapshot;
                CompletableFuture<IndexCoverageSnapshot> future = triggerRefresh(false);
                try {
                    return future.get(INITIAL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    return liveSnapshot;
                }
            }
            if (needsImmediateRefresh(snapshot)) {
                CompletableFuture<IndexCoverageSnapshot> future = triggerRefresh(false);
                try {
                    return future.get(INITIAL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    return snapshot;
                }
            }
            if (shouldRefresh()) {
                triggerRefresh(true);
            }
            return snapshot;
        }

        CompletableFuture<IndexCoverageSnapshot> future = triggerRefresh(false);
        try {
            return future.get(INITIAL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return pendingSnapshot();
        }
    }

    private boolean shouldRefresh() {
        return lastSnapshotAt == null || lastSnapshotAt.plus(SNAPSHOT_TTL).isBefore(Instant.now());
    }

    private boolean needsImmediateRefresh(IndexCoverageSnapshot snapshot) {
        return snapshot.totalProjects() == 0 && !vectorStore.getAllDocuments().isEmpty();
    }

    private boolean progressChanged(IndexCoverageSnapshot snapshot, ReindexProgressService.ReindexProgress progress) {
        return !Objects.equals(snapshot.reindexRunId(), progress.runId())
            || !Objects.equals(snapshot.reindexProjectName(), progress.projectName())
                || snapshot.reindexQueuePosition() != progress.queuePosition()
                || snapshot.reindexQueueTotal() != progress.queueTotal()
            || snapshot.reindexInProgress() != progress.inProgress()
                || snapshot.reindexIndexedFiles() != progress.indexedFiles()
                || snapshot.reindexFailedFiles() != progress.failedFiles()
                || !Objects.equals(snapshot.reindexStartedAt(), progress.startedAt())
                || !Objects.equals(snapshot.reindexUpdatedAt(), progress.updatedAt())
                || !Objects.equals(snapshot.reindexLastIndexedPath(), progress.lastIndexedPath())
                || !Objects.equals(snapshot.reindexLastErrorPath(), progress.lastErrorPath());
    }

    public void invalidate() {
        cachedSnapshot = null;
        lastSnapshotAt = null;
        triggerRefresh(false);
    }

    public void markDirty() {
        if (cachedSnapshot != null) {
            lastSnapshotAt = Instant.EPOCH;
        }
    }

    private CompletableFuture<IndexCoverageSnapshot> triggerRefresh(boolean onlyIfStale) {
        if (onlyIfStale && !shouldRefresh() && cachedSnapshot != null) {
            return CompletableFuture.completedFuture(cachedSnapshot);
        }
        CompletableFuture<IndexCoverageSnapshot> current = refreshFuture;
        if (current != null && !current.isDone()) {
            return current;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            return refreshFuture != null
                    ? refreshFuture
                    : CompletableFuture.completedFuture(cachedSnapshot == null ? pendingSnapshot() : cachedSnapshot);
        }

        CompletableFuture<IndexCoverageSnapshot> future = CompletableFuture.supplyAsync(this::computeSnapshot)
                .whenComplete((computedSnapshot, error) -> {
                    if (computedSnapshot != null) {
                        ReindexProgressService.ReindexProgress latestProgress = reindexProgressService.snapshot();
                        cachedSnapshot = withProgress(computedSnapshot, latestProgress);
                        lastSnapshotAt = Instant.now();
                    }
                    refreshInFlight.set(false);
                });
        refreshFuture = future;
        return future;
    }

    private IndexCoverageSnapshot withProgress(
            IndexCoverageSnapshot snapshot,
            ReindexProgressService.ReindexProgress progress) {
        return new IndexCoverageSnapshot(
                Instant.now().toString(),
                progress.runId(),
                progress.projectName(),
                progress.queuePosition(),
                progress.queueTotal(),
                progress.inProgress(),
                progress.indexedFiles(),
                progress.failedFiles(),
                progress.startedAt(),
                progress.updatedAt(),
                progress.lastIndexedPath(),
                progress.lastErrorPath(),
                snapshot.watchRoot(),
                snapshot.embeddingModel(),
                snapshot.embeddingCoverageThresholdPercent(),
                snapshot.embeddingCoverageThresholdMet(),
                snapshot.embeddingCoverageShortfallPercent(),
                snapshot.fileCoverageThresholdPercent(),
                snapshot.fileCoverageThresholdMet(),
                snapshot.fileCoverageThresholdShortfallPercent(),
                snapshot.chunkCoverageThresholdPercent(),
                snapshot.chunkCoverageThresholdMet(),
                snapshot.chunkCoverageThresholdShortfallPercent(),
                snapshot.semanticCoverageThresholdPercent(),
                snapshot.semanticCoverageThresholdMet(),
                snapshot.semanticCoverageThresholdShortfallPercent(),
                snapshot.warnings(),
                snapshot.totalProjects(),
                snapshot.totalIndexableFiles(),
                snapshot.embeddedFiles(),
                snapshot.fileCoveragePercent(),
                snapshot.indexedDocuments(),
                snapshot.totalChunks(),
                snapshot.embeddedChunks(),
                snapshot.chunkCoveragePercent(),
                snapshot.totalSemanticNodes(),
                snapshot.embeddedSemanticNodes(),
                snapshot.semanticCoveragePercent(),
                snapshot.projects());
    }

    private IndexCoverageSnapshot computeSnapshot() {
        Path root = properties.getWatchRoot();
        double overallThreshold = overallCoverageThreshold();
        ReindexProgressService.ReindexProgress progress = reindexProgressService.snapshot();
        if (root == null || !Files.exists(root)) {
            return new IndexCoverageSnapshot(
                    Instant.now().toString(),
                    progress.runId(),
                    progress.projectName(),
                    progress.queuePosition(),
                    progress.queueTotal(),
                    progress.inProgress(),
                    progress.indexedFiles(),
                    progress.failedFiles(),
                    progress.startedAt(),
                    progress.updatedAt(),
                    progress.lastIndexedPath(),
                    progress.lastErrorPath(),
                    root == null ? "" : root.toString(),
                    properties.getOllama().getEmbeddingModel(),
                    overallThreshold,
                    false,
                    overallThreshold,
                    fileCoverageThreshold(),
                    false,
                    fileCoverageThreshold(),
                    chunkCoverageThreshold(),
                    false,
                    chunkCoverageThreshold(),
                    semanticCoverageThreshold(),
                    false,
                    semanticCoverageThreshold(),
                    List.of("Coverage snapshot is unavailable because the configured watch root does not exist."),
                    0,
                    0,
                    0,
                    0.0,
                    0,
                    0,
                    0,
                    0.0,
                    0,
                    0,
                    0.0,
                    List.of());
        }

        List<Document> documents = vectorStore.getAllDocuments();
        Map<String, List<Document>> docsByFile = documents.stream()
                .collect(Collectors.groupingBy(document -> Objects.toString(document.getMetadata().get("filePath"), "")));

        Map<String, ProjectAccumulator> projects = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isIndexable)
                    .forEach(path -> analyzeFile(root, path, docsByFile.getOrDefault(path.toString(), List.of()), projects));
        } catch (Exception ignored) {
            return new IndexCoverageSnapshot(
                    Instant.now().toString(),
                    progress.runId(),
                    progress.projectName(),
                    progress.queuePosition(),
                    progress.queueTotal(),
                    progress.inProgress(),
                    progress.indexedFiles(),
                    progress.failedFiles(),
                    progress.startedAt(),
                    progress.updatedAt(),
                    progress.lastIndexedPath(),
                    progress.lastErrorPath(),
                    root.toString(),
                    properties.getOllama().getEmbeddingModel(),
                    overallThreshold,
                    false,
                    overallThreshold,
                    fileCoverageThreshold(),
                    false,
                    fileCoverageThreshold(),
                    chunkCoverageThreshold(),
                    false,
                    chunkCoverageThreshold(),
                    semanticCoverageThreshold(),
                    false,
                    semanticCoverageThreshold(),
                    List.of("Coverage snapshot failed while walking the watch root."),
                    0,
                    0,
                    0,
                    0.0,
                    documents.size(),
                    0,
                    0,
                    0.0,
                    0,
                    0,
                    0.0,
                    List.of());
        }

        List<IndexCoverageProject> projectSummaries = projects.values().stream()
                .map(ProjectAccumulator::toSummary)
                .sorted(Comparator.comparing(IndexCoverageProject::projectName))
                .toList();

        int totalFiles = projectSummaries.stream().mapToInt(IndexCoverageProject::indexableFiles).sum();
        int embeddedFiles = projectSummaries.stream().mapToInt(IndexCoverageProject::embeddedFiles).sum();
        int totalChunks = projectSummaries.stream().mapToInt(IndexCoverageProject::totalChunks).sum();
        int embeddedChunks = projectSummaries.stream().mapToInt(IndexCoverageProject::embeddedChunks).sum();
        int totalSemanticNodes = projectSummaries.stream().mapToInt(IndexCoverageProject::totalSemanticNodes).sum();
        int embeddedSemanticNodes = projectSummaries.stream().mapToInt(IndexCoverageProject::embeddedSemanticNodes).sum();
        double fileCoveragePercent = percent(embeddedFiles, totalFiles);
        double chunkCoveragePercent = percent(embeddedChunks, totalChunks);
        double semanticCoveragePercent = percent(embeddedSemanticNodes, totalSemanticNodes);
        ThresholdState fileThresholdState = thresholdState(fileCoveragePercent, totalFiles, fileCoverageThreshold());
        ThresholdState chunkThresholdState = thresholdState(chunkCoveragePercent, totalChunks, chunkCoverageThreshold());
        ThresholdState semanticThresholdState = thresholdState(semanticCoveragePercent, totalSemanticNodes, semanticCoverageThreshold());
        ThresholdState overallThresholdState = overallState(fileThresholdState, chunkThresholdState, semanticThresholdState);
        List<String> warnings = snapshotWarnings(projectSummaries, fileThresholdState, chunkThresholdState, semanticThresholdState);

        return new IndexCoverageSnapshot(
                Instant.now().toString(),
            progress.runId(),
            progress.projectName(),
            progress.queuePosition(),
            progress.queueTotal(),
            progress.inProgress(),
            progress.indexedFiles(),
            progress.failedFiles(),
            progress.startedAt(),
            progress.updatedAt(),
            progress.lastIndexedPath(),
            progress.lastErrorPath(),
                root.toString(),
                properties.getOllama().getEmbeddingModel(),
            overallThreshold,
            overallThresholdState.met(),
            overallThresholdState.shortfallPercent(),
            fileThresholdState.thresholdPercent(),
            fileThresholdState.met(),
            fileThresholdState.shortfallPercent(),
            chunkThresholdState.thresholdPercent(),
            chunkThresholdState.met(),
            chunkThresholdState.shortfallPercent(),
            semanticThresholdState.thresholdPercent(),
            semanticThresholdState.met(),
            semanticThresholdState.shortfallPercent(),
            warnings,
                projectSummaries.size(),
                totalFiles,
                embeddedFiles,
            fileCoveragePercent,
                documents.size(),
                totalChunks,
                embeddedChunks,
            chunkCoveragePercent,
                totalSemanticNodes,
                embeddedSemanticNodes,
            semanticCoveragePercent,
                projectSummaries);
    }

    private IndexCoverageSnapshot pendingSnapshot() {
        Path root = properties.getWatchRoot();
        double overallThreshold = overallCoverageThreshold();
        ReindexProgressService.ReindexProgress progress = reindexProgressService.snapshot();
        return new IndexCoverageSnapshot(
                Instant.now().toString(),
            progress.runId(),
            progress.projectName(),
            progress.queuePosition(),
            progress.queueTotal(),
            progress.inProgress(),
            progress.indexedFiles(),
            progress.failedFiles(),
            progress.startedAt(),
            progress.updatedAt(),
            progress.lastIndexedPath(),
            progress.lastErrorPath(),
                root == null ? "" : root.toString(),
                properties.getOllama().getEmbeddingModel(),
            overallThreshold,
            false,
            overallThreshold,
            fileCoverageThreshold(),
            false,
            fileCoverageThreshold(),
            chunkCoverageThreshold(),
            false,
            chunkCoverageThreshold(),
            semanticCoverageThreshold(),
            false,
            semanticCoverageThreshold(),
            List.of("Coverage snapshot is warming up."),
                0,
                0,
                0,
                0.0,
                0,
                0,
                0,
                0.0,
                0,
                0,
                0.0,
                List.of());
    }

    private void analyzeFile(
            Path root,
            Path path,
            List<Document> documents,
            Map<String, ProjectAccumulator> projects) {
        try {
            String source = Files.readString(path);
            List<CodeChunk> expectedChunks = documentSplitter.split(root, path, source);
            Set<String> embeddedIds = documents.stream().map(Document::getId).collect(Collectors.toSet());
            String firstSegment = projectSegment(root, path);
            ProjectAccumulator project = projects.computeIfAbsent(
                    firstSegment,
                    ignored -> new ProjectAccumulator(firstSegment, projectPath(root, firstSegment)));
            project.addFile(toFileAnalysis(root, path, expectedChunks, documents, embeddedIds));
        } catch (Exception ignored) {
        }
    }

    private FileAnalysis toFileAnalysis(
            Path root,
            Path path,
            List<CodeChunk> expectedChunks,
            List<Document> documents,
            Set<String> embeddedIds) {
        List<CodeChunk> semanticChunks = expectedChunks.stream()
                .filter(chunk -> !"file".equals(chunk.nodeType()))
                .toList();
        List<CodeChunk> embeddedChunks = expectedChunks.stream()
                .filter(chunk -> embeddedIds.contains(chunk.id()))
                .toList();
        List<CodeChunk> embeddedSemantic = semanticChunks.stream()
                .filter(chunk -> embeddedIds.contains(chunk.id()))
                .toList();
        List<String> embeddedSymbols = embeddedSemantic.stream()
                .map(CodeChunk::symbolName)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .limit(6)
                .toList();
        List<String> missingSymbols = semanticChunks.stream()
                .filter(chunk -> !embeddedIds.contains(chunk.id()))
                .map(CodeChunk::symbolName)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .limit(6)
                .toList();
        String lastIndexedAt = documents.stream()
                .map(document -> Objects.toString(document.getMetadata().get("indexedAt"), ""))
                .filter(value -> !value.isBlank())
                .max(String::compareTo)
                .orElse("");

        Map<String, NodeCounter> nodeTypeCoverage = new TreeMap<>();
        for (CodeChunk chunk : semanticChunks) {
            NodeCounter counter = nodeTypeCoverage.computeIfAbsent(chunk.nodeType(), ignored -> new NodeCounter());
            counter.total++;
            if (embeddedIds.contains(chunk.id())) {
            counter.embedded++;
            }
        }

        return new FileAnalysis(
            new IndexCoverageFile(
                normalize(root.relativize(path).toString()),
                expectedChunks.isEmpty() ? detectLanguage(path) : expectedChunks.getFirst().language(),
                !embeddedChunks.isEmpty(),
                expectedChunks.size(),
                embeddedChunks.size(),
                percent(embeddedChunks.size(), expectedChunks.size()),
                semanticChunks.size(),
                embeddedSemantic.size(),
                percent(embeddedSemantic.size(), semanticChunks.size()),
                lastIndexedAt,
                embeddedSymbols,
                missingSymbols),
            nodeTypeCoverage);
    }

    private boolean isIndexable(Path path) {
        if (path == null || gitIgnoreMatcherService.shouldIgnore(path)) {
            return false;
        }
        return projectIndexabilityService.isIndexable(path);
    }

    private String projectSegment(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : root.getFileName().toString();
    }

    private String projectPath(Path root, String segment) {
        if (segment.equals(root.getFileName().toString())) {
            return root.toString();
        }
        return root.resolve(segment).toString();
    }

    private String detectLanguage(Path filePath) {
        return projectIndexabilityService.detectLanguage(filePath);
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private double percent(int actual, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round((actual * 10000.0) / total) / 100.0;
    }

    private double overallCoverageThreshold() {
        return properties.getMetrics().getEmbeddingCoverageThresholdPercent();
    }

    private double fileCoverageThreshold() {
        return properties.getMetrics().getFileCoverageThresholdPercent();
    }

    private double chunkCoverageThreshold() {
        return properties.getMetrics().getChunkCoverageThresholdPercent();
    }

    private double semanticCoverageThreshold() {
        return properties.getMetrics().getSemanticCoverageThresholdPercent();
    }

    private ThresholdState thresholdState(double coveragePercent, int total, double thresholdPercent) {
        if (total <= 0) {
            return new ThresholdState(thresholdPercent, false, thresholdPercent);
        }
        boolean met = coveragePercent + COVERAGE_EPSILON >= thresholdPercent;
        double shortfall = Math.max(0.0, Math.round((thresholdPercent - coveragePercent) * 100.0) / 100.0);
        return new ThresholdState(thresholdPercent, met, shortfall);
    }

    private ThresholdState overallState(
            ThresholdState fileThresholdState,
            ThresholdState chunkThresholdState,
            ThresholdState semanticThresholdState) {
        double shortfall = Math.max(
                fileThresholdState.shortfallPercent(),
                Math.max(chunkThresholdState.shortfallPercent(), semanticThresholdState.shortfallPercent()));
        return new ThresholdState(
                overallCoverageThreshold(),
                fileThresholdState.met() && chunkThresholdState.met() && semanticThresholdState.met(),
                shortfall);
    }

    private List<String> snapshotWarnings(
            List<IndexCoverageProject> projectSummaries,
            ThresholdState fileThresholdState,
            ThresholdState chunkThresholdState,
            ThresholdState semanticThresholdState) {
        List<String> warnings = new ArrayList<>();
        appendThresholdWarning(warnings, "file coverage", fileThresholdState);
        appendThresholdWarning(warnings, "chunk coverage", chunkThresholdState);
        appendThresholdWarning(warnings, "semantic coverage", semanticThresholdState);

        List<String> flaggedProjects = projectSummaries.stream()
                .filter(project -> !project.embeddingCoverageThresholdMet())
                .map(IndexCoverageProject::projectName)
                .limit(5)
                .toList();
        if (!flaggedProjects.isEmpty()) {
            warnings.add("Projects below threshold: " + String.join(", ", flaggedProjects)
                    + (projectSummaries.size() > flaggedProjects.size() ? ", ..." : ""));
        }
        return warnings;
    }

    private void appendThresholdWarning(List<String> warnings, String label, ThresholdState thresholdState) {
        if (!thresholdState.met()) {
            warnings.add(String.format(
                    Locale.ROOT,
                    "%s is %.2f%% short of the %.2f%% threshold.",
                    label,
                    thresholdState.shortfallPercent(),
                    thresholdState.thresholdPercent()));
        }
    }

    private final class ProjectAccumulator {
        private final String projectName;
        private final String projectPath;
        private final List<IndexCoverageFile> files = new ArrayList<>();
        private final Map<String, Counter> nodeTypeCoverage = new LinkedHashMap<>();
        private int indexableFiles;
        private int embeddedFiles;
        private int totalChunks;
        private int embeddedChunks;
        private int totalSemanticNodes;
        private int embeddedSemanticNodes;
        private String lastIndexedAt = "";

        private ProjectAccumulator(String projectName, String projectPath) {
            this.projectName = projectName;
            this.projectPath = projectPath;
        }

        private void addFile(FileAnalysis analysis) {
            IndexCoverageFile file = analysis.file();
            files.add(file);
            indexableFiles++;
            if (file.indexed()) {
                embeddedFiles++;
            }
            totalChunks += file.totalChunks();
            embeddedChunks += file.embeddedChunks();
            totalSemanticNodes += file.totalSemanticNodes();
            embeddedSemanticNodes += file.embeddedSemanticNodes();
            if (!file.lastIndexedAt().isBlank() && file.lastIndexedAt().compareTo(lastIndexedAt) > 0) {
                lastIndexedAt = file.lastIndexedAt();
            }
            updateNodeTypes(file, analysis.nodeTypeCoverage());
        }

        private void updateNodeTypes(IndexCoverageFile file, Map<String, NodeCounter> fileNodeTypes) {
            for (Map.Entry<String, NodeCounter> entry : fileNodeTypes.entrySet()) {
                Counter counter = nodeTypeCoverage.computeIfAbsent(entry.getKey(), ignored -> new Counter());
                counter.total += entry.getValue().total;
                counter.embedded += entry.getValue().embedded;
            }
            Counter fileCounter = nodeTypeCoverage.computeIfAbsent(file.language() + "-files", ignored -> new Counter());
            fileCounter.total += 1;
            fileCounter.embedded += file.indexed() ? 1 : 0;
        }

        private IndexCoverageProject toSummary() {
            double fileCoveragePercent = percent(embeddedFiles, indexableFiles);
            double chunkCoveragePercent = percent(embeddedChunks, totalChunks);
            double semanticCoveragePercent = percent(embeddedSemanticNodes, totalSemanticNodes);
            ThresholdState fileThresholdState = thresholdState(fileCoveragePercent, indexableFiles, fileCoverageThreshold());
            ThresholdState chunkThresholdState = thresholdState(chunkCoveragePercent, totalChunks, chunkCoverageThreshold());
            ThresholdState semanticThresholdState = thresholdState(
                semanticCoveragePercent,
                totalSemanticNodes,
                semanticCoverageThreshold());
            ThresholdState overallThresholdState = overallState(fileThresholdState, chunkThresholdState, semanticThresholdState);
            List<String> warnings = new ArrayList<>();
            appendThresholdWarning(warnings, "file coverage", fileThresholdState);
            appendThresholdWarning(warnings, "chunk coverage", chunkThresholdState);
            appendThresholdWarning(warnings, "semantic coverage", semanticThresholdState);
            List<IndexCoverageNodeType> nodeTypes = nodeTypeCoverage.entrySet().stream()
                    .map(entry -> new IndexCoverageNodeType(
                            entry.getKey(),
                            entry.getValue().total,
                            entry.getValue().embedded,
                            percent(entry.getValue().embedded, entry.getValue().total)))
                    .sorted(Comparator.comparing(IndexCoverageNodeType::nodeType))
                    .toList();
            List<IndexCoverageFile> sortedFiles = files.stream()
                    .sorted(Comparator.comparing(IndexCoverageFile::relativePath))
                    .toList();
            return new IndexCoverageProject(
                    projectName,
                    projectPath,
                    overallCoverageThreshold(),
                    overallThresholdState.met(),
                    overallThresholdState.shortfallPercent(),
                    fileThresholdState.thresholdPercent(),
                    fileThresholdState.met(),
                    fileThresholdState.shortfallPercent(),
                    chunkThresholdState.thresholdPercent(),
                    chunkThresholdState.met(),
                    chunkThresholdState.shortfallPercent(),
                    semanticThresholdState.thresholdPercent(),
                    semanticThresholdState.met(),
                    semanticThresholdState.shortfallPercent(),
                    warnings,
                    indexableFiles,
                    embeddedFiles,
                    fileCoveragePercent,
                    totalChunks,
                    embeddedChunks,
                    chunkCoveragePercent,
                    totalSemanticNodes,
                    embeddedSemanticNodes,
                    semanticCoveragePercent,
                    lastIndexedAt,
                    nodeTypes,
                    sortedFiles);
        }
    }

    private static final class Counter {
        private int total;
        private int embedded;
    }

    private static final class NodeCounter {
        private int total;
        private int embedded;
    }

    private record ThresholdState(double thresholdPercent, boolean met, double shortfallPercent) {
    }

    private record FileAnalysis(IndexCoverageFile file, Map<String, NodeCounter> nodeTypeCoverage) {
    }
}