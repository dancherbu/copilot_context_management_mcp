package dev.dancherbu.ccm.watch;

import dev.dancherbu.ccm.config.CcmProperties;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class GitIgnoreMatcherService {

    private final CcmProperties properties;
    private final ConcurrentMap<Path, List<Rule>> ruleCache = new ConcurrentHashMap<>();

    public GitIgnoreMatcherService(CcmProperties properties) {
        this.properties = properties;
    }

    public boolean shouldIgnore(Path path) {
        Path root = properties.getWatchRoot();
        if (root == null || path == null) {
            return true;
        }

        Path normalized = path.normalize();
        if (!normalized.startsWith(root.normalize())) {
            return true;
        }

        for (Path part : normalized) {
            if (properties.getIgnore().getBuiltins().contains(part.toString())) {
                return true;
            }
        }

        if (matchesBuiltInPatterns(root.normalize(), normalized)) {
            return true;
        }

        boolean ignored = false;
        for (Rule rule : getRules(root)) {
            if (rule.matches(root, normalized)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private boolean matchesBuiltInPatterns(Path root, Path candidate) {
        Path relativePath = root.relativize(candidate);
        Path fileName = candidate.getFileName();
        for (String pattern : properties.getIgnore().getPatterns()) {
            PathMatcher matcher = Rule.compileMatcher(pattern);
            PathMatcher fileNameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace("\\", "/"));
            if (matcher.matches(relativePath)
                    || (fileName != null && (matcher.matches(fileName) || fileNameMatcher.matches(fileName)))) {
                return true;
            }
        }
        return false;
    }

    private List<Rule> getRules(Path root) {
        return ruleCache.computeIfAbsent(root.normalize(), this::loadRules);
    }

    private List<Rule> loadRules(Path root) {
        List<Rule> rules = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName() != null && path.getFileName().toString().equals(".gitignore"))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .forEach(path -> rules.addAll(readRules(path)));
        } catch (IOException ignored) {
            return List.of();
        }
        return rules;
    }

    private List<Rule> readRules(Path gitIgnoreFile) {
        try {
            List<String> lines = Files.readAllLines(gitIgnoreFile);
            Path baseDir = gitIgnoreFile.getParent();
            List<Rule> rules = new ArrayList<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                boolean negated = line.startsWith("!");
                String pattern = negated ? line.substring(1) : line;
                rules.add(new Rule(baseDir, pattern, negated));
            }
            return rules;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private record Rule(Path baseDir, String pattern, boolean negated, PathMatcher matcher, boolean directoryOnly) {
        Rule(Path baseDir, String pattern, boolean negated) {
            this(baseDir, pattern, negated, compileMatcher(pattern), pattern.endsWith("/"));
        }

        boolean matches(Path root, Path candidate) {
            Path normalizedBaseDir = baseDir.normalize();
            Path normalizedCandidate = candidate.normalize();
            if (!normalizedCandidate.startsWith(normalizedBaseDir)) {
                return false;
            }

            Path relativeToRoot = root.relativize(candidate);
            Path relativeToBase = baseDir.relativize(candidate);
            if (directoryOnly && !Files.isDirectory(candidate) && !candidate.toString().endsWith("/")) {
                return false;
            }
            return matcher.matches(relativeToBase)
                    || matcher.matches(relativeToRoot)
                    || matcher.matches(relativeToRoot.getFileName());
        }

        private static PathMatcher compileMatcher(String rawPattern) {
            String normalized = rawPattern.replace("\\", "/");
            boolean anchored = normalized.contains("/");
            boolean directoryOnly = normalized.endsWith("/");
            if (directoryOnly) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (!anchored) {
                normalized = "**/" + normalized;
            }
            normalized = normalized.startsWith("/") ? normalized.substring(1) : normalized;
            return FileSystems.getDefault().getPathMatcher("glob:" + normalized);
        }
    }
}
