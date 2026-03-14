package dev.dancherbu.ccm.watch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dancherbu.ccm.config.CcmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIgnoreMatcherServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void gitIgnoreRulesOnlyApplyWithinOwningRepository() throws IOException {
        Path repoA = Files.createDirectories(tempDir.resolve("repo-a"));
        Path repoB = Files.createDirectories(tempDir.resolve("repo-b"));

        Files.writeString(repoA.resolve(".gitignore"), "*\n");
        Path repoAFile = Files.writeString(repoA.resolve("Ignored.java"), "class Ignored {}\n");
        Path repoBFile = Files.writeString(repoB.resolve("Included.java"), "class Included {}\n");

        GitIgnoreMatcherService matcherService = new GitIgnoreMatcherService(properties(tempDir));

        assertTrue(matcherService.shouldIgnore(repoAFile));
        assertFalse(matcherService.shouldIgnore(repoBFile));
    }

    @Test
    void builtInSensitivePatternsAreIgnored() throws IOException {
        Path secretFile = Files.writeString(tempDir.resolve("copilot-secrets.md"), "secret\n");
        Path envFile = Files.writeString(tempDir.resolve(".env.local"), "VALUE=1\n");
        Path sourceDirectory = Files.createDirectories(tempDir.resolve("src"));
        Path sourceFile = Files.writeString(sourceDirectory.resolve("Main.java"), "class Main {}\n");

        GitIgnoreMatcherService matcherService = new GitIgnoreMatcherService(properties(tempDir));

        assertTrue(matcherService.shouldIgnore(secretFile));
        assertTrue(matcherService.shouldIgnore(envFile));
        assertFalse(matcherService.shouldIgnore(sourceFile));
    }

    private CcmProperties properties(Path watchRoot) {
        CcmProperties properties = new CcmProperties();
        properties.setWatchRoot(watchRoot);
        properties.getIgnore().setBuiltins(List.of(".git", "node_modules", "target", "dist", "build"));
        properties.getIgnore().setPatterns(List.of("copilot-secrets.md", ".env", ".env.*", "*.pem", "*.key"));
        return properties;
    }
}