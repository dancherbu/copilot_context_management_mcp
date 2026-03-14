package dev.dancherbu.ccm.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReindexProgressService {

    private static final Logger logger = LoggerFactory.getLogger(ReindexProgressService.class);
    private static final String REDIS_KEY = "ccm:mcp:reindex:progress";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ReindexProgressService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String start(String projectName, int queuePosition, int queueTotal) {
        String now = Instant.now().toString();
        String runId = UUID.randomUUID().toString();
        ReindexProgress started = new ReindexProgress(
                runId,
                projectName == null ? "" : projectName,
                Math.max(queuePosition, 1),
                Math.max(queueTotal, 1),
                true,
                0,
                0,
                now,
                now,
                "",
                "");
        writeState(started);
        return runId;
    }

    public void markIndexed(String runId, String filePath) {
        update(runId, snapshot -> new ReindexProgress(
                snapshot.runId(),
                snapshot.projectName(),
            snapshot.queuePosition(),
            snapshot.queueTotal(),
                snapshot.inProgress(),
                snapshot.indexedFiles() + 1,
                snapshot.failedFiles(),
                snapshot.startedAt(),
                Instant.now().toString(),
                filePath == null ? "" : filePath,
                snapshot.lastErrorPath()));
    }

    public void markFailed(String runId, String filePath) {
        update(runId, snapshot -> new ReindexProgress(
                snapshot.runId(),
                snapshot.projectName(),
            snapshot.queuePosition(),
            snapshot.queueTotal(),
                snapshot.inProgress(),
                snapshot.indexedFiles(),
                snapshot.failedFiles() + 1,
                snapshot.startedAt(),
                Instant.now().toString(),
                snapshot.lastIndexedPath(),
                filePath == null ? "" : filePath));
    }

    public void finish(String runId) {
        update(runId, snapshot -> new ReindexProgress(
                snapshot.runId(),
                snapshot.projectName(),
            snapshot.queuePosition(),
            snapshot.queueTotal(),
                false,
                snapshot.indexedFiles(),
                snapshot.failedFiles(),
                snapshot.startedAt(),
                Instant.now().toString(),
                snapshot.lastIndexedPath(),
                snapshot.lastErrorPath()));
    }

    public ReindexProgress snapshot() {
        try {
            String raw = redisTemplate.opsForValue().get(REDIS_KEY);
            if (raw == null || raw.isBlank()) {
                return empty();
            }
            return objectMapper.readValue(raw, ReindexProgress.class);
        } catch (Exception ex) {
            logger.warn("Unable to read reindex progress from Redis", ex);
            return empty();
        }
    }

    private ReindexProgress empty() {
        return new ReindexProgress(
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
                "");
    }

    private void update(String runId, ProgressUpdater updater) {
        ReindexProgress current = snapshot();
        if (runId == null || runId.isBlank() || !runId.equals(current.runId())) {
            logger.debug(
                    "Ignoring reindex progress update for stale runId {} (current runId {})",
                    runId,
                    current.runId());
            return;
        }
        writeState(updater.apply(current));
    }

    private void writeState(ReindexProgress progress) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, objectMapper.writeValueAsString(progress));
        } catch (Exception ex) {
            logger.warn("Unable to write reindex progress to Redis", ex);
        }
    }

    public record ReindexProgress(
            String runId,
            String projectName,
            int queuePosition,
            int queueTotal,
            boolean inProgress,
            int indexedFiles,
            int failedFiles,
            String startedAt,
            String updatedAt,
            String lastIndexedPath,
            String lastErrorPath) {}

    @FunctionalInterface
    private interface ProgressUpdater {
        ReindexProgress apply(ReindexProgress current);
    }
}