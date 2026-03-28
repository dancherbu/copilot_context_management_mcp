package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.config.CcmProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpMetricsPageController {

    private static final String PAGE_PATH = "static/mcp-metrics.html";
    private static final String API_KEY_PLACEHOLDER = "__DEFAULT_API_KEY__";
    private static final String AUTH_REQUIRED_PLACEHOLDER = "__AUTH_REQUIRED__";

    private final ObjectMapper objectMapper;
    private final CcmProperties properties;

    public McpMetricsPageController(ObjectMapper objectMapper, CcmProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @GetMapping(value = {"/", "/index.html", "/mcp-metrics.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public String metricsPage() throws IOException {
        String template = readTemplate();
        String configuredKey = properties.getSecurity().getApiKey();
        String serializedKey = objectMapper.writeValueAsString(configuredKey == null ? "" : configuredKey);
        boolean authRequired = configuredKey != null && !configuredKey.isBlank();
        return template
                .replace(API_KEY_PLACEHOLDER, serializedKey)
                .replace(AUTH_REQUIRED_PLACEHOLDER, Boolean.toString(authRequired));
    }

    private String readTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource(PAGE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}