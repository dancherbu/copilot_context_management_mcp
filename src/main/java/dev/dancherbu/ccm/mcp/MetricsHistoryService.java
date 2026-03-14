package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.MetricsBenchResponse;
import dev.dancherbu.ccm.model.MetricsBenchRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MetricsHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsHistoryService.class);
    private static final TypeReference<List<MetricsBenchRun>> RUN_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path historyFile;
    private final int historyLimit;
    private final ReentrantLock lock = new ReentrantLock();

    public MetricsHistoryService(ObjectMapper objectMapper, CcmProperties properties) {
        this.objectMapper = objectMapper;
        this.historyFile = Path.of(properties.getMetrics().getHistoryFile());
        this.historyLimit = Math.max(1, properties.getMetrics().getHistoryLimit());
    }

    public List<MetricsBenchRun> recordRun(String query, List<String> tools, MetricsBenchResponse response) {
        lock.lock();
        try {
            List<MetricsBenchRun> runs = new ArrayList<>(loadRuns());
            runs.addFirst(new MetricsBenchRun(
                    UUID.randomUUID().toString(),
                    Instant.now().toString(),
                    query,
                    List.copyOf(tools),
                    response.sessionInitMs(),
                    response.toolsRun(),
                    response.medianRoundtripMs(),
                    response.totalEstimatedPayloadTokens(),
                    response.errorCount(),
                    response.results()));
            if (runs.size() > historyLimit) {
                runs = new ArrayList<>(runs.subList(0, historyLimit));
            }
            persist(runs);
            return List.copyOf(runs);
        } finally {
            lock.unlock();
        }
    }

    public List<MetricsBenchRun> getRecentRuns() {
        lock.lock();
        try {
            return List.copyOf(loadRuns());
        } finally {
            lock.unlock();
        }
    }

    private List<MetricsBenchRun> loadRuns() {
        if (!Files.exists(historyFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(historyFile.toFile(), RUN_LIST_TYPE);
        } catch (IOException ex) {
            logger.warn("Unable to read metrics history from {}", historyFile, ex);
            return List.of();
        }
    }

    private void persist(List<MetricsBenchRun> runs) {
        try {
            Path parent = historyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyFile.toFile(), runs);
        } catch (IOException ex) {
            logger.warn("Unable to persist metrics history to {}", historyFile, ex);
        }
    }
}