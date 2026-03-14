package dev.dancherbu.ccm.vector;

import dev.dancherbu.ccm.model.CodeChunk;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.stereotype.Component;

@Component
public class CodebaseVectorStore {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseVectorStore.class);

    private final SimpleVectorStore vectorStore;
    private final Path backingFile;
    private final Map<String, List<String>> fileChunkIds = new ConcurrentHashMap<>();
    private final AtomicInteger bulkUpdateDepth = new AtomicInteger(0);

    public CodebaseVectorStore(SimpleVectorStore vectorStore, Path backingFile) {
        this.vectorStore = vectorStore;
        this.backingFile = backingFile;
        pruneMissingFiles();
        rebuildFileChunkIds();
    }

    public synchronized void upsertFileChunks(List<CodeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        String filePath = chunks.getFirst().filePath();
        removeFile(filePath);

        List<Document> documents = chunks.stream()
                .map(this::toDocument)
                .toList();
        this.vectorStore.add(documents);
        this.fileChunkIds.put(filePath, documents.stream().map(Document::getId).toList());
        persistIfNeeded();
    }

    public synchronized void removeFile(String filePath) {
        List<String> chunkIds = this.fileChunkIds.remove(filePath);
        if (chunkIds != null && !chunkIds.isEmpty()) {
            this.vectorStore.delete(chunkIds);
            persistIfNeeded();
        }
    }

    public void beginBulkUpdate() {
        bulkUpdateDepth.incrementAndGet();
    }

    public synchronized void endBulkUpdate() {
        int remaining = bulkUpdateDepth.decrementAndGet();
        if (remaining <= 0) {
            bulkUpdateDepth.set(0);
            persist();
        }
    }

    public List<Document> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder().query(query).topK(topK).build();
        List<Document> matches = this.vectorStore.similaritySearch(request);
        return matches == null ? List.of() : matches;
    }

    public List<Document> searchInProject(String query, int topK, String projectPath) {
        List<Document> matches = search(query, topK * 4);
        if (projectPath == null || projectPath.isBlank()) {
            return matches.stream().limit(topK).toList();
        }
        String normalizedProjectPath = normalizePrefix(projectPath);
        return matches.stream()
                .filter(document -> normalizePrefix(Objects.toString(document.getMetadata().get("filePath"), ""))
                        .startsWith(normalizedProjectPath))
                .limit(topK)
                .toList();
    }

    public synchronized List<String> getKnownFiles() {
        return new ArrayList<>(this.fileChunkIds.keySet());
    }

    public synchronized List<String> getKnownFiles(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return getKnownFiles();
        }
        String normalizedProjectPath = normalizePrefix(projectPath);
        return this.fileChunkIds.keySet().stream()
                .filter(filePath -> normalizePrefix(filePath).startsWith(normalizedProjectPath))
                .sorted()
                .toList();
    }

    public synchronized List<Document> getAllDocuments() {
        return rawStore().values().stream().map(content -> content.toDocument(0.0)).toList();
    }

    public synchronized List<Document> getAllDocuments(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return getAllDocuments();
        }
        String normalizedProjectPath = normalizePrefix(projectPath);
        return rawStore().values().stream()
                .map(content -> content.toDocument(0.0))
                .filter(document -> normalizePrefix(Objects.toString(document.getMetadata().get("filePath"), ""))
                        .startsWith(normalizedProjectPath))
                .collect(Collectors.toList());
    }

    public synchronized List<Document> getDocumentsForFile(String filePath) {
        return rawStore().values().stream()
                .filter(content -> filePath.equals(Objects.toString(content.getMetadata().get("filePath"), "")))
                .map(content -> content.toDocument(0.0))
                .toList();
    }

    public synchronized void pruneMissingFiles() {
        List<String> staleIds = rawStore().values().stream()
                .filter(content -> {
                    String filePath = Objects.toString(content.getMetadata().get("filePath"), "");
                    return filePath.isBlank() || !Files.exists(Path.of(filePath));
                })
                .map(SimpleVectorStoreContent::getId)
                .toList();
        if (!staleIds.isEmpty()) {
            logger.info("Pruning {} stale vector-store entries whose files no longer exist", staleIds.size());
            this.vectorStore.delete(staleIds);
            persist();
        }
        rebuildFileChunkIds();
    }

    private Document toDocument(CodeChunk chunk) {
        return Document.builder()
                .id(chunk.id())
                .text(chunk.snippet())
                .metadata(Map.of(
                        "workspacePath", chunk.workspacePath(),
                        "filePath", chunk.filePath(),
                        "language", chunk.language(),
                        "node_type", chunk.nodeType(),
                        "symbolName", Objects.toString(chunk.symbolName(), ""),
                        "startLine", chunk.startLine(),
                        "endLine", chunk.endLine(),
                        "checksum", chunk.checksum(),
                        "indexedAt", chunk.indexedAt().toString()))
                .build();
    }

    private void persist() {
        try {
            Path parent = this.backingFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.vectorStore.save(this.backingFile.toFile());
        } catch (IOException ex) {
            logger.warn("Unable to persist vector store to {}", this.backingFile, ex);
        }
    }

    private void persistIfNeeded() {
        if (bulkUpdateDepth.get() > 0) {
            return;
        }
        persist();
    }

    private void rebuildFileChunkIds() {
        this.fileChunkIds.clear();
        for (SimpleVectorStoreContent content : rawStore().values()) {
            String filePath = Objects.toString(content.getMetadata().get("filePath"), "");
            if (filePath.isBlank()) {
                continue;
            }
            this.fileChunkIds.computeIfAbsent(filePath, ignored -> new ArrayList<>()).add(content.getId());
        }
    }

    private String normalizePrefix(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    @SuppressWarnings("unchecked")
    private Map<String, SimpleVectorStoreContent> rawStore() {
        try {
            Field storeField = SimpleVectorStore.class.getDeclaredField("store");
            storeField.setAccessible(true);
            Object value = storeField.get(this.vectorStore);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, SimpleVectorStoreContent>) map;
            }
        } catch (Exception ex) {
            logger.warn("Unable to inspect underlying simple vector store state", ex);
        }
        return Map.of();
    }
}
