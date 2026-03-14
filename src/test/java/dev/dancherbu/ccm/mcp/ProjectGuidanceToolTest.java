package dev.dancherbu.ccm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.IndexCoverageSnapshot;
import dev.dancherbu.ccm.model.ProjectGuidance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectGuidanceToolTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversReferencedSecretFileFromInstructions() throws Exception {
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(
                tempDir.resolve("copilot-instructions.md"),
                "# Project notes\n"
                        + "Secrets are stored in `docs/local-secrets.txt` and must stay local.\n"
                        + "Use docker compose for deployment.\n");
        Files.writeString(tempDir.resolve("docs/local-secrets.txt"), "TOKEN=local-only\n");

        IndexInsightsService indexInsightsService = mock(IndexInsightsService.class);
        when(indexInsightsService.snapshot()).thenReturn(emptySnapshot());

        CcmProperties properties = new CcmProperties();
        properties.setWatchRoot(tempDir);

        ProjectGuidanceTool tool = new ProjectGuidanceTool(
                indexInsightsService,
                properties,
                new ToolPlanningSupportService());

        ProjectGuidance result = tool.get_project_guidance(null, "verbose", null, null, true);

        assertThat(result.discoveredFiles().stream().map(file -> file.filePath()).toList())
                .contains(tempDir.resolve("docs/local-secrets.txt").toString());
        assertThat(result.secretLocations())
                .anyMatch(line -> line.contains("docs/local-secrets.txt"));
        assertThat(result.includeSecrets()).isTrue();
    }

    @Test
    void discoversExplicitSecretFileKeyFromInstructions() throws Exception {
        Files.createDirectories(tempDir.resolve("ops"));
        Files.writeString(
                tempDir.resolve("copilot-instructions.md"),
                "# Project notes\n"
                        + "secret_file: ops/runtime-secrets.yaml\n"
                        + "deploy with docker compose\n");
        Files.writeString(tempDir.resolve("ops/runtime-secrets.yaml"), "api_key: abc\n");

        IndexInsightsService indexInsightsService = mock(IndexInsightsService.class);
        when(indexInsightsService.snapshot()).thenReturn(emptySnapshot());

        CcmProperties properties = new CcmProperties();
        properties.setWatchRoot(tempDir);

        ProjectGuidanceTool tool = new ProjectGuidanceTool(
                indexInsightsService,
                properties,
                new ToolPlanningSupportService());

        ProjectGuidance result = tool.get_project_guidance(null, "verbose", null, null, true);

        assertThat(result.discoveredFiles().stream().map(file -> file.filePath()).toList())
                .contains(tempDir.resolve("ops/runtime-secrets.yaml").toString());
        assertThat(result.secretLocations())
                .anyMatch(line -> line.contains("runtime-secrets.yaml"));
    }

    private IndexCoverageSnapshot emptySnapshot() {
        String now = Instant.now().toString();
        return new IndexCoverageSnapshot(
                now,
                "",
                "",
                0,
                0,
                false,
                0,
                0,
                "",
                "",
                "",
                "",
                tempDir.toString(),
                "",
                85.0,
                false,
                85.0,
                85.0,
                false,
                85.0,
                85.0,
                false,
                85.0,
                85.0,
                false,
                85.0,
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of());
    }
}