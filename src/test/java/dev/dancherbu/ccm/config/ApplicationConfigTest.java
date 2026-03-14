package dev.dancherbu.ccm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

class ApplicationConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void simpleVectorStoreQuarantinesCorruptBackingFileAndStartsEmpty() throws Exception {
        Path backingFile = tempDir.resolve("vector-store.json");
        Files.writeString(backingFile, "{\"broken\": [1, 2, 3}");

        CcmProperties properties = new CcmProperties();
        properties.setVectorStoreFile(backingFile.toString());

        ApplicationConfig config = new ApplicationConfig();
        SimpleVectorStore vectorStore = config.simpleVectorStore(mock(EmbeddingModel.class), properties);

        assertThat(vectorStore).isNotNull();
        assertThat(Files.exists(backingFile)).isFalse();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .anyMatch(name -> name.startsWith("vector-store.json.corrupt-"));
        }
    }
}