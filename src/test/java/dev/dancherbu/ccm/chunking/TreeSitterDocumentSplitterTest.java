package dev.dancherbu.ccm.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.index.ProjectIndexabilityService;
import dev.dancherbu.ccm.model.CodeChunk;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeSitterDocumentSplitterTest {

    private final TreeSitterDocumentSplitter splitter = new TreeSitterDocumentSplitter(
            properties(), new ProjectIndexabilityService(properties()));

    @Test
    void splitsJavaFilesIntoClassAndMethodChunks() {
        List<CodeChunk> chunks = splitter.split(
                Path.of("/workspace/projects"),
                Path.of("/workspace/projects/sample/src/main/java/example/SampleService.java"),
                """
                package example;

                public class SampleService {
                    public void firstMethod() {
                    }

                    public void secondMethod() {
                    }
                }
                """);

        assertThat(chunks).extracting(CodeChunk::nodeType)
                .contains("class_declaration", "method_declaration");
        assertThat(chunks).extracting(CodeChunk::symbolName)
                .contains("SampleService", "firstMethod", "secondMethod");
    }

    @Test
    void truncatesNonJavaFileChunksToConfiguredSnippetLimit() {
        List<CodeChunk> chunks = splitter.split(
                Path.of("/workspace/projects"),
                Path.of("/workspace/projects/sample/frontend/src/App.jsx"),
                "x".repeat(120));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().language()).isEqualTo("javascript");
        assertThat(chunks.getFirst().snippet()).hasSize(40);
    }

    @Test
    void truncatesJavaSemanticChunksToConfiguredSnippetLimit() {
        String longMethodBody = "x".repeat(220);
        List<CodeChunk> chunks = splitter.split(
                Path.of("/workspace/projects"),
                Path.of("/workspace/projects/sample/src/main/java/example/LongService.java"),
                """
                package example;

                public class LongService {
                    public String oversizedMethod() {
                        return "%s";
                    }
                }
                """.formatted(longMethodBody));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(chunk -> chunk.snippet().length() <= 40);
    }

    private CcmProperties properties() {
        CcmProperties properties = new CcmProperties();
        properties.setWatchRoot(Path.of("/workspace/projects"));
        properties.setMaxSnippetCharacters(40);
        return properties;
    }
}