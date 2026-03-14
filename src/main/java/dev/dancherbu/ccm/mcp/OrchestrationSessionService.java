package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrchestrationSessionService {

    private static final Logger logger = LoggerFactory.getLogger(OrchestrationSessionService.class);
    private static final int MAX_SESSIONS = 500;
    private static final String REDIS_KEY_PREFIX = "ccm:mcp:orchestration:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public String startOrResume(String requestedSessionId) {
        String sessionId = (requestedSessionId == null || requestedSessionId.isBlank())
                ? UUID.randomUUID().toString()
                : requestedSessionId.trim();
        SessionState state = resolveState(sessionId);
        if (state == null) {
            state = new SessionState();
        }
        state.updatedAt = Instant.now().toString();
        sessions.put(sessionId, state);
        writeRedis(sessionId, state);
        evictIfNeeded();
        return sessionId;
    }

    public void rememberDefaults(String sessionId, String projectName, String responseMode, Integer tokenBudget) {
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        if (projectName != null && !projectName.isBlank()) {
            state.projectName = projectName;
        }
        if (responseMode != null && !responseMode.isBlank()) {
            state.responseMode = responseMode;
        }
        if (tokenBudget != null && tokenBudget > 0) {
            state.tokenBudget = tokenBudget;
        }
        state.updatedAt = Instant.now().toString();
        writeRedis(sessionId, state);
    }

    public void rememberContextHash(String sessionId, String key, String contextHash) {
        if (contextHash == null || contextHash.isBlank()) {
            return;
        }
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        state.contextHashes.put(key, contextHash);
        state.updatedAt = Instant.now().toString();
        writeRedis(sessionId, state);
    }

    public String knownContextHash(String sessionId, String key) {
        SessionState state = resolveState(sessionId);
        if (state == null) {
            return "";
        }
        return state.contextHashes.getOrDefault(key, "");
    }

    public Map<String, Object> snapshot(String sessionId) {
        SessionState state = resolveState(sessionId);
        if (state == null) {
            return Map.of(
                    "sessionId", sessionId,
                    "known", false,
                    "projectName", "",
                    "responseMode", "",
                    "tokenBudget", 0,
                    "contextHashes", Map.of(),
                    "updatedAt", "");
        }
        return Map.of(
                "sessionId", sessionId,
                "known", true,
                "projectName", state.projectName,
                "responseMode", state.responseMode,
                "tokenBudget", state.tokenBudget,
                "contextHashes", new LinkedHashMap<>(state.contextHashes),
                "updatedAt", state.updatedAt);
    }

    private SessionState resolveState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionState local = sessions.get(sessionId);
        if (local != null) {
            return local;
        }
        SessionState remote = readRedis(sessionId);
        if (remote != null) {
            sessions.put(sessionId, remote);
            return remote;
        }
        return null;
    }

    private SessionState readRedis(String sessionId) {
        if (redisTemplate == null || objectMapper == null) {
            return null;
        }
        try {
            if (redisTemplate.opsForValue() == null) {
                return null;
            }
            String raw = redisTemplate.opsForValue().get(redisKey(sessionId));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, SessionState.class);
        } catch (Exception ex) {
            logger.warn("Unable to read orchestration session from Redis", ex);
            return null;
        }
    }

    private void writeRedis(String sessionId, SessionState state) {
        if (redisTemplate == null || objectMapper == null || state == null) {
            return;
        }
        try {
            if (redisTemplate.opsForValue() == null) {
                return;
            }
            redisTemplate.opsForValue().set(redisKey(sessionId), objectMapper.writeValueAsString(state), SESSION_TTL);
        } catch (Exception ex) {
            logger.warn("Unable to write orchestration session to Redis", ex);
        }
    }

    private String redisKey(String sessionId) {
        return REDIS_KEY_PREFIX + sessionId;
    }

    private void evictIfNeeded() {
        if (sessions.size() <= MAX_SESSIONS) {
            return;
        }
        String oldestSession = sessions.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> a.updatedAt.compareTo(b.updatedAt)))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (oldestSession != null) {
            sessions.remove(oldestSession);
        }
    }

    private static class SessionState {
        public String projectName = "";
        public String responseMode = "";
        public int tokenBudget = 0;
        public String updatedAt = Instant.now().toString();
        public Map<String, String> contextHashes = new LinkedHashMap<>();

        public SessionState() {
        }
    }
}