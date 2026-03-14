package dev.dancherbu.ccm.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ccm")
public class CcmProperties {

    private Path watchRoot;
    private String vectorStoreFile;
    private String semanticCachePrefix;
    private int analysisTokenLimit;
    private int maxSnippetCharacters;
    private final Ollama ollama = new Ollama();
    private final Ignore ignore = new Ignore();
    private final Metrics metrics = new Metrics();
    private final Security security = new Security();

    public Path getWatchRoot() {
        return watchRoot;
    }

    public void setWatchRoot(Path watchRoot) {
        this.watchRoot = watchRoot;
    }

    public String getVectorStoreFile() {
        return vectorStoreFile;
    }

    public void setVectorStoreFile(String vectorStoreFile) {
        this.vectorStoreFile = vectorStoreFile;
    }

    public String getSemanticCachePrefix() {
        return semanticCachePrefix;
    }

    public void setSemanticCachePrefix(String semanticCachePrefix) {
        this.semanticCachePrefix = semanticCachePrefix;
    }

    public int getAnalysisTokenLimit() {
        return analysisTokenLimit;
    }

    public void setAnalysisTokenLimit(int analysisTokenLimit) {
        this.analysisTokenLimit = analysisTokenLimit;
    }

    public int getMaxSnippetCharacters() {
        return maxSnippetCharacters;
    }

    public void setMaxSnippetCharacters(int maxSnippetCharacters) {
        this.maxSnippetCharacters = maxSnippetCharacters;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public Ignore getIgnore() {
        return ignore;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Ollama {
        private String chatModel;
        private String embeddingModel;

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class Ignore {
        private List<String> builtins = new ArrayList<>();
        private List<String> patterns = new ArrayList<>();

        public List<String> getBuiltins() {
            return builtins;
        }

        public void setBuiltins(List<String> builtins) {
            this.builtins = builtins;
        }

        public List<String> getPatterns() {
            return patterns;
        }

        public void setPatterns(List<String> patterns) {
            this.patterns = patterns;
        }
    }

    public static class Metrics {
        private String historyFile;
        private String usageFile;
        private int historyLimit = 20;
        private double embeddingCoverageThresholdPercent = 85.0;
        private double fileCoverageThresholdPercent = 85.0;
        private double chunkCoverageThresholdPercent = 85.0;
        private double semanticCoverageThresholdPercent = 85.0;

        public String getHistoryFile() {
            return historyFile;
        }

        public void setHistoryFile(String historyFile) {
            this.historyFile = historyFile;
        }

        public String getUsageFile() {
            return usageFile;
        }

        public void setUsageFile(String usageFile) {
            this.usageFile = usageFile;
        }

        public int getHistoryLimit() {
            return historyLimit;
        }

        public void setHistoryLimit(int historyLimit) {
            this.historyLimit = historyLimit;
        }

        public double getEmbeddingCoverageThresholdPercent() {
            return embeddingCoverageThresholdPercent;
        }

        public void setEmbeddingCoverageThresholdPercent(double embeddingCoverageThresholdPercent) {
            this.embeddingCoverageThresholdPercent = embeddingCoverageThresholdPercent;
        }

        public double getFileCoverageThresholdPercent() {
            return fileCoverageThresholdPercent;
        }

        public void setFileCoverageThresholdPercent(double fileCoverageThresholdPercent) {
            this.fileCoverageThresholdPercent = fileCoverageThresholdPercent;
        }

        public double getChunkCoverageThresholdPercent() {
            return chunkCoverageThresholdPercent;
        }

        public void setChunkCoverageThresholdPercent(double chunkCoverageThresholdPercent) {
            this.chunkCoverageThresholdPercent = chunkCoverageThresholdPercent;
        }

        public double getSemanticCoverageThresholdPercent() {
            return semanticCoverageThresholdPercent;
        }

        public void setSemanticCoverageThresholdPercent(double semanticCoverageThresholdPercent) {
            this.semanticCoverageThresholdPercent = semanticCoverageThresholdPercent;
        }
    }

    public static class Security {
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
