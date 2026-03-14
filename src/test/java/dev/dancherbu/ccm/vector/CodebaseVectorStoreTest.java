package dev.dancherbu.ccm.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;

class CodebaseVectorStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void pruneMissingFilesRemovesStalePersistedEntriesAndRebuildsKnownFiles() throws Exception {
        Path existingFile = Files.createFile(tempDir.resolve("ApplicationConfig.java"));
        Path missingFile = tempDir.resolve("MissingConfig.java");

        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(mock(EmbeddingModel.class)).build();
        Map<String, SimpleVectorStoreContent> backingStore = rawStore(simpleVectorStore);
        backingStore.put(
                "keep",
                new SimpleVectorStoreContent(
                        "keep",
                        "class ApplicationConfig {}",
                        Map.of("filePath", existingFile.toString()),
                        new float[] {1.0f, 2.0f}));
        backingStore.put(
                "drop",
                new SimpleVectorStoreContent(
                        "drop",
                        "class MissingConfig {}",
                        Map.of("filePath", missingFile.toString()),
                        new float[] {2.0f, 3.0f}));

        CodebaseVectorStore codebaseVectorStore = new CodebaseVectorStore(simpleVectorStore, tempDir.resolve("vector-store.json"));
        codebaseVectorStore.pruneMissingFiles();

        assertThat(rawStore(simpleVectorStore)).containsOnlyKeys("keep");
        assertThat(codebaseVectorStore.getKnownFiles()).containsExactly(existingFile.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, SimpleVectorStoreContent> rawStore(SimpleVectorStore simpleVectorStore) throws Exception {
        Field storeField = SimpleVectorStore.class.getDeclaredField("store");
        storeField.setAccessible(true);
        return (Map<String, SimpleVectorStoreContent>) storeField.get(simpleVectorStore);
    }
}