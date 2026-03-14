package dev.dancherbu.ccm.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.FileImpact;
import dev.dancherbu.ccm.support.ChecksumUtils;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SemanticCacheService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCacheService.class);
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CcmProperties properties;

    public SemanticCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, CcmProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<FileImpact> getImpacts(String query) {
        try {
            String raw = redisTemplate.opsForValue().get(key(query));
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            logger.warn("Unable to read semantic cache", ex);
            return null;
        }
    }

    public void putImpacts(String query, List<FileImpact> impacts) {
        try {
            redisTemplate.opsForValue().set(key(query), objectMapper.writeValueAsString(impacts), TTL);
        } catch (Exception ex) {
            logger.warn("Unable to write semantic cache", ex);
        }
    }

    public void evictByFilePath(String filePath) {
        try {
            String pattern = properties.getSemanticCachePrefix() + ":*";
            var keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return;
            }
            for (String key : keys) {
                String payload = redisTemplate.opsForValue().get(key);
                if (payload != null && payload.contains(filePath)) {
                    redisTemplate.delete(key);
                }
            }
        } catch (Exception ex) {
            logger.warn("Unable to evict semantic cache entries for {}", filePath, ex);
        }
    }

    private String key(String query) {
        return properties.getSemanticCachePrefix() + ":" + ChecksumUtils.sha256(query.strip().toLowerCase());
    }
}
