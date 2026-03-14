package dev.dancherbu.ccm.index;

import dev.dancherbu.ccm.config.CcmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ProjectIndexabilityService {

    private static final Set<String> BASE_EXTENSIONS = Set.of(
            ".java",
            ".xml",
            ".properties",
            ".yml",
            ".yaml",
            ".json",
            ".md",
            ".txt",
            ".sql",
            ".sh",
            ".toml");

    private static final Set<String> BASE_FILE_NAMES = Set.of("dockerfile");

        private static final Map<String, Set<String>> MANIFEST_EXTENSIONS = Map.ofEntries(
            Map.entry(
                "package.json",
                Set.of(
                    ".js",
                    ".jsx",
                    ".cjs",
                    ".mjs",
                    ".ts",
                    ".tsx",
                    ".css",
                    ".scss",
                    ".sass",
                    ".less",
                    ".html",
                    ".mdx",
                    ".liquid",
                    ".vue",
                    ".svelte",
                    ".astro")),
            Map.entry("requirements.txt", Set.of(".py")),
            Map.entry("pyproject.toml", Set.of(".py")),
            Map.entry("setup.py", Set.of(".py")),
            Map.entry("pom.xml", Set.of(".java")),
            Map.entry("build.gradle", Set.of(".java", ".kt", ".kts", ".gradle")),
            Map.entry("build.gradle.kts", Set.of(".java", ".kt", ".kts", ".gradle")),
            Map.entry("Cargo.toml", Set.of(".rs")),
            Map.entry("pubspec.yaml", Set.of(".dart")),
            Map.entry("docker-compose.yml", Set.of()),
            Map.entry("docker-compose.yaml", Set.of()),
            Map.entry("Dockerfile", Set.of()));

    private final CcmProperties properties;
    private final Map<Path, ProjectProfile> profileCache = new ConcurrentHashMap<>();

    public ProjectIndexabilityService(CcmProperties properties) {
        this.properties = properties;
    }

    public boolean isIndexable(Path path) {
        if (path == null) {
            return false;
        }

        ProjectProfile profile = profileFor(path);
        String fileName = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (profile.fileNames().contains(fileName)) {
            return true;
        }

        String extension = extensionOf(fileName);
        return !extension.isBlank() && profile.extensions().contains(extension);
    }

    public String detectLanguage(Path path) {
        if (path == null || path.getFileName() == null) {
            return "text";
        }

        String fileName = path.getFileName().toString();
        if ("Dockerfile".equalsIgnoreCase(fileName)) {
            return "docker";
        }

        String extension = extensionOf(fileName.toLowerCase(Locale.ROOT));
        return switch (extension) {
            case ".java" -> "java";
            case ".xml" -> "xml";
            case ".js", ".jsx", ".cjs", ".mjs" -> "javascript";
            case ".ts", ".tsx" -> "typescript";
            case ".py" -> "python";
            case ".kt", ".kts" -> "kotlin";
            case ".rs" -> "rust";
            case ".dart" -> "dart";
            case ".css", ".scss", ".sass", ".less" -> "styles";
            case ".html", ".liquid", ".vue", ".svelte", ".astro" -> "web";
            case ".properties", ".yml", ".yaml", ".json", ".toml", ".gradle" -> "config";
            case ".sql" -> "sql";
            case ".sh" -> "shell";
            case ".md", ".mdx" -> "markdown";
            case ".txt" -> "text";
            default -> "text";
        };
    }

    private ProjectProfile profileFor(Path path) {
        return profileCache.computeIfAbsent(projectRootFor(path), this::loadProfile);
    }

    private Path projectRootFor(Path path) {
        Path watchRoot = properties.getWatchRoot();
        if (watchRoot == null || path == null || !path.startsWith(watchRoot)) {
            return watchRoot == null ? path.getParent() : watchRoot;
        }

        Path relative = watchRoot.relativize(path);
        if (relative.getNameCount() <= 1) {
            return watchRoot;
        }

        return watchRoot.resolve(relative.getName(0));
    }

    private ProjectProfile loadProfile(Path projectRoot) {
        Set<String> extensions = new LinkedHashSet<>(BASE_EXTENSIONS);
        Set<String> fileNames = new LinkedHashSet<>(BASE_FILE_NAMES);
        if (projectRoot == null) {
            return new ProjectProfile(extensions, fileNames);
        }

        for (Map.Entry<String, Set<String>> entry : MANIFEST_EXTENSIONS.entrySet()) {
            if (Files.exists(projectRoot.resolve(entry.getKey()))) {
                extensions.addAll(entry.getValue());
                if ("Dockerfile".equalsIgnoreCase(entry.getKey())) {
                    fileNames.add("dockerfile");
                }
            }
        }

        return new ProjectProfile(extensions, fileNames);
    }

    private String extensionOf(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0) {
            return "";
        }
        return fileName.substring(extensionIndex);
    }

    private record ProjectProfile(Set<String> extensions, Set<String> fileNames) {
    }
}