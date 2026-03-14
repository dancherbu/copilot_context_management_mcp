package dev.dancherbu.ccm.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dancherbu.ccm.config.CcmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIndexabilityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void packageJsonProjectsIncludeFrontendSourceTypes() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("famamemomo"));
        Files.writeString(projectRoot.resolve("package.json"), "{}\n");

        ProjectIndexabilityService service = new ProjectIndexabilityService(properties(tempDir));

        assertThat(service.isIndexable(projectRoot.resolve("src/App.jsx"))).isTrue();
        assertThat(service.isIndexable(projectRoot.resolve("src/index.ts"))).isTrue();
        assertThat(service.isIndexable(projectRoot.resolve("src/styles.css"))).isTrue();
        assertThat(service.detectLanguage(projectRoot.resolve("src/App.jsx"))).isEqualTo("javascript");
        assertThat(service.detectLanguage(projectRoot.resolve("src/index.ts"))).isEqualTo("typescript");
    }

    @Test
    void projectsWithoutMatchingManifestDoNotAdmitFrontendExtensions() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("java-service"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");

        ProjectIndexabilityService service = new ProjectIndexabilityService(properties(tempDir));

        assertThat(service.isIndexable(projectRoot.resolve("src/main/java/example/App.java"))).isTrue();
        assertThat(service.isIndexable(projectRoot.resolve("src/App.jsx"))).isFalse();
    }

    @Test
    void dockerfileIsHandledAsAnExactFileName() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("service"));

        ProjectIndexabilityService service = new ProjectIndexabilityService(properties(tempDir));

        assertThat(service.isIndexable(projectRoot.resolve("Dockerfile"))).isTrue();
        assertThat(service.detectLanguage(projectRoot.resolve("Dockerfile"))).isEqualTo("docker");
    }

    private CcmProperties properties(Path watchRoot) {
        CcmProperties properties = new CcmProperties();
        properties.setWatchRoot(watchRoot);
        return properties;
    }
}