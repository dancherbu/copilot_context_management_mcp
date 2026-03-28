package dev.dancherbu.ccm.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LocalInferenceGuard implements ApplicationRunner {

    private static final Set<String> LOCAL_HOST_ALIASES =
            Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0", "host.docker.internal", "ollama");

    private final CcmProperties properties;

    @Value("${spring.ai.ollama.base-url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    public LocalInferenceGuard(CcmProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getOllama().isLocalOnly()) {
            return;
        }
        if (!isLocalEndpoint(ollamaBaseUrl)) {
            throw new IllegalStateException(
                    "Refusing to start with a non-local Ollama endpoint: '" + ollamaBaseUrl
                            + "'. Set spring.ai.ollama.base-url to a local endpoint or set ccm.ollama.local-only=false to override.");
        }
    }

    private boolean isLocalEndpoint(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }

        String normalized = baseUrl.contains("://") ? baseUrl : "http://" + baseUrl;
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return false;
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (LOCAL_HOST_ALIASES.contains(normalizedHost)) {
            return true;
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
