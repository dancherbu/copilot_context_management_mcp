package dev.dancherbu.ccm.index;

import dev.dancherbu.ccm.chunking.TreeSitterDocumentSplitter;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.mcp.IndexInsightsService;
import dev.dancherbu.ccm.model.CodeChunk;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import dev.dancherbu.ccm.watch.GitIgnoreMatcherService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReindexCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ReindexCoordinator.class);
    private static final int SNAPSHOT_DIRTY_INTERVAL = 25;

    private final CcmProperties properties;
    private final GitIgnoreMatcherService gitIgnoreMatcherService;
    private final ProjectIndexabilityService projectIndexabilityService;
    private final ReindexProgressService reindexProgressService;
    private final TreeSitterDocumentSplitter documentSplitter;
    private final CodebaseVectorStore vectorStore;
    private final IndexInsightsService indexInsightsService;

    public ReindexCoordinator(
            CcmProperties properties,
            GitIgnoreMatcherService gitIgnoreMatcherService,
            ProjectIndexabilityService projectIndexabilityService,
            ReindexProgressService reindexProgressService,
            TreeSitterDocumentSplitter documentSplitter,
            CodebaseVectorStore vectorStore,
            IndexInsightsService indexInsightsService) {
        this.properties = properties;
        this.gitIgnoreMatcherService = gitIgnoreMatcherService;
        this.projectIndexabilityService = projectIndexabilityService;
        this.reindexProgressService = reindexProgressService;
        this.documentSplitter = documentSplitter;
        this.vectorStore = vectorStore;
        this.indexInsightsService = indexInsightsService;
    }

    public void fullReindex() {
        Path root = properties.getWatchRoot();
        if (root == null || !Files.exists(root)) {
            logger.warn("Watch root does not exist: {}", root);
            return;
        }

        logger.info("Starting full reindex for {}", root);
        List<Path> projectRoots = discoverProjectRoots(root);
        int queueTotal = projectRoots.size();
        for (int projectIndex = 0; projectIndex < projectRoots.size(); projectIndex++) {
            Path projectRoot = projectRoots.get(projectIndex);
            String projectName = projectRoot.getFileName() == null
                    ? projectRoot.toString()
                    : projectRoot.getFileName().toString();
            String runId = reindexProgressService.start(projectName, projectIndex + 1, queueTotal);
            indexInsightsService.markDirty();
            AtomicInteger indexedFiles = new AtomicInteger();
            vectorStore.beginBulkUpdate();
            try (var stream = Files.walk(projectRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isIndexable)
                        .forEach(path -> {
                            indexFile(path, runId);
                            int current = indexedFiles.incrementAndGet();
                            reindexProgressService.markIndexed(runId, path.toString());
                            // Marking dirty periodically lets metrics refresh during long reindex runs.
                            if (current % SNAPSHOT_DIRTY_INTERVAL == 0) {
                                indexInsightsService.markDirty();
                            }
                        });
                indexInsightsService.invalidate();
                logger.info("Completed full reindex for {}. Indexed {} files", projectRoot, indexedFiles.get());
            } catch (Exception ex) {
                reindexProgressService.markFailed(runId, projectRoot.toString());
                logger.warn("Full reindex failed for {}", projectRoot, ex);
            } finally {
                vectorStore.endBulkUpdate();
                reindexProgressService.finish(runId);
            }
        }
    }

    public void handleCreate(Path path) {
        if (isIndexable(path)) {
            indexFile(path);
            indexInsightsService.invalidate();
        }
    }

    public void handleModify(Path path) {
        if (isIndexable(path)) {
            indexFile(path);
            indexInsightsService.invalidate();
        }
    }

    public void handleDelete(Path path) {
        vectorStore.removeFile(path.toString());
        indexInsightsService.invalidate();
    }

    private void indexFile(Path path) {
        indexFile(path, null);
    }

    private void indexFile(Path path, String runId) {
        try {
            String source = Files.readString(path);
            List<CodeChunk> chunks = documentSplitter.split(properties.getWatchRoot(), path, source);
            vectorStore.upsertFileChunks(chunks);
            logger.debug("Indexed {} into {} chunks", path, chunks.size());
        } catch (Exception ex) {
            if (runId != null && !runId.isBlank()) {
                reindexProgressService.markFailed(runId, path.toString());
            }
            logger.warn("Unable to index {}", path, ex);
        }
    }

    private List<Path> discoverProjectRoots(Path root) {
        List<Path> projectRoots = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(projectRoots::add);
        } catch (IOException ex) {
            logger.warn("Unable to enumerate project roots under {}", root, ex);
        }
        if (projectRoots.isEmpty()) {
            projectRoots.add(root);
        }
        return projectRoots;
    }

    private boolean isIndexable(Path path) {
        if (path == null || gitIgnoreMatcherService.shouldIgnore(path)) {
            return false;
        }
        return projectIndexabilityService.isIndexable(path);
    }
}
