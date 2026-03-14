package dev.dancherbu.ccm.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class ToolPlanningSupportServiceTest {

    private final ToolPlanningSupportService service = new ToolPlanningSupportService();

    @Test
    void mapsTestsAndDependencyEdgesBySymbols() {
        Document source = document(
                "/workspace/projects/repo/src/main/java/dev/example/BuildContextBundleTool.java",
                "BuildContextBundleTool",
                "class_declaration",
                1,
                40,
                "class BuildContextBundleTool { private final ToolPlanningSupportService planningSupportService; }");
        Document dependency = document(
                "/workspace/projects/repo/src/main/java/dev/example/ToolPlanningSupportService.java",
                "ToolPlanningSupportService",
                "class_declaration",
                1,
                80,
                "class ToolPlanningSupportService { } ");
        Document test = document(
                "/workspace/projects/repo/src/test/java/dev/example/BuildContextBundleToolTest.java",
                "BuildContextBundleToolTest",
                "class_declaration",
                1,
                30,
                "class BuildContextBundleToolTest { void coversBuildContextBundleTool() { new BuildContextBundleTool(); } }");

        List<String> relatedTests = service.relatedTests(
                List.of("/workspace/projects/repo/src/main/java/dev/example/BuildContextBundleTool.java"),
                List.of(source, dependency, test),
                List.of(
                        "/workspace/projects/repo/src/test/java/dev/example/BuildContextBundleToolTest.java",
                        "/workspace/projects/repo/src/main/java/dev/example/ToolPlanningSupportService.java"));

        List<String> dependencyEdges = service.dependencyEdges(
                List.of("/workspace/projects/repo/src/main/java/dev/example/BuildContextBundleTool.java"),
                List.of(source, dependency, test));

        assertThat(relatedTests)
                .contains("/workspace/projects/repo/src/test/java/dev/example/BuildContextBundleToolTest.java");
        assertThat(dependencyEdges)
                .anyMatch(edge -> edge.contains("BuildContextBundleTool.java")
                        && edge.contains("ToolPlanningSupportService.java")
                        && edge.contains("ToolPlanningSupportService"));
    }

    private Document document(
            String filePath, String symbolName, String nodeType, int startLine, int endLine, String snippet) {
        return Document.builder()
                .id(filePath + ':' + startLine)
                .text(snippet)
                .metadata(Map.of(
                        "filePath", filePath,
                        "symbolName", symbolName,
                        "node_type", nodeType,
                        "startLine", startLine,
                        "endLine", endLine,
                        "language", "java"))
                .build();
    }
}