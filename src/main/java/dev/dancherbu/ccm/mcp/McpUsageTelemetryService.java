package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.McpUsageSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpUsageTelemetryService {

    private static final Logger logger = LoggerFactory.getLogger(McpUsageTelemetryService.class);
    private static final int MAX_TRACKED_SESSIONS = 10_000;
    private static final String DEFAULT_USAGE_FILE = ".vector-store/usage-metrics.json";

    private final ObjectMapper objectMapper;
    private final Path usageFile;
    private final ReentrantLock lock = new ReentrantLock();

    private long sseSessionsOpened;
    private long mcpMessagesReceived;
    private final Map<String, String> sessionLastSeenAt = new HashMap<>();

    private String lastSseOpenedAt = "";
    private String lastMcpMessageAt = "";

    public McpUsageTelemetryService(ObjectMapper objectMapper, CcmProperties properties) {
        this.objectMapper = objectMapper;
        String configuredPath = properties.getMetrics().getUsageFile();
        this.usageFile = Path.of(StringUtils.hasText(configuredPath) ? configuredPath : DEFAULT_USAGE_FILE);
        loadState();
    }

    public void recordSseOpen() {
        lock.lock();
        try {
            sseSessionsOpened += 1;
            lastSseOpenedAt = Instant.now().toString();
            persistState();
        } finally {
            lock.unlock();
        }
    }

    public void recordMcpMessage(String sessionId) {
        lock.lock();
        try {
            mcpMessagesReceived += 1;
            lastMcpMessageAt = Instant.now().toString();
            if (sessionId != null && !sessionId.isBlank()) {
                sessionLastSeenAt.put(sessionId, lastMcpMessageAt);
                trimSessions();
            }
            persistState();
        } finally {
            lock.unlock();
        }
    }

    public McpUsageSnapshot snapshot() {
        lock.lock();
        try {
            return new McpUsageSnapshot(
                    Instant.now().toString(),
                    sseSessionsOpened,
                    mcpMessagesReceived,
                    sessionLastSeenAt.size(),
                    lastSseOpenedAt,
                    lastMcpMessageAt);
        } finally {
            lock.unlock();
        }
    }

    public McpUsageSnapshot reset() {
        lock.lock();
        try {
            sseSessionsOpened = 0;
            mcpMessagesReceived = 0;
            sessionLastSeenAt.clear();
            lastSseOpenedAt = "";
            lastMcpMessageAt = "";
            persistState();
            return snapshotInternal();
        } finally {
            lock.unlock();
        }
    }

    private McpUsageSnapshot snapshotInternal() {
        return new McpUsageSnapshot(
                Instant.now().toString(),
                sseSessionsOpened,
                mcpMessagesReceived,
                sessionLastSeenAt.size(),
                lastSseOpenedAt,
                lastMcpMessageAt);
    }

    private void loadState() {
        if (!Files.exists(usageFile)) {
            return;
        }
        try {
            PersistedUsageState state = objectMapper.readValue(usageFile.toFile(), PersistedUsageState.class);
            if (state == null) {
                return;
            }
            sseSessionsOpened = Math.max(0, state.sseSessionsOpened());
            mcpMessagesReceived = Math.max(0, state.mcpMessagesReceived());
            sessionLastSeenAt.clear();
            if (state.sessionLastSeenAt() != null) {
                sessionLastSeenAt.putAll(state.sessionLastSeenAt());
                while (sessionLastSeenAt.size() > MAX_TRACKED_SESSIONS) {
                    trimSessions();
                }
            }
            lastSseOpenedAt = state.lastSseOpenedAt() == null ? "" : state.lastSseOpenedAt();
            lastMcpMessageAt = state.lastMcpMessageAt() == null ? "" : state.lastMcpMessageAt();
        } catch (IOException ex) {
            logger.warn("Unable to read usage telemetry from {}", usageFile, ex);
        }
    }

    private void persistState() {
        PersistedUsageState state = new PersistedUsageState(
                sseSessionsOpened,
                mcpMessagesReceived,
                Map.copyOf(sessionLastSeenAt),
                lastSseOpenedAt,
                lastMcpMessageAt);
        try {
            Path parent = usageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(usageFile.toFile(), state);
        } catch (IOException ex) {
            logger.warn("Unable to persist usage telemetry to {}", usageFile, ex);
        }
    }

    private void trimSessions() {
        if (sessionLastSeenAt.size() <= MAX_TRACKED_SESSIONS) {
            return;
        }
        String oldest = sessionLastSeenAt.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        if (oldest != null) {
            sessionLastSeenAt.remove(oldest);
        }
    }

    private record PersistedUsageState(
            long sseSessionsOpened,
            long mcpMessagesReceived,
            Map<String, String> sessionLastSeenAt,
            String lastSseOpenedAt,
            String lastMcpMessageAt) {}
}