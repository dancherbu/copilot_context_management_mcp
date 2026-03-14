package dev.dancherbu.ccm.config;

import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpApiKeyWebFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-CCM-API-Key";

    private final CcmProperties properties;

    public McpApiKeyWebFilter(CcmProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String configuredApiKey = properties.getSecurity().getApiKey();
        if (!StringUtils.hasText(configuredApiKey) || !requiresAuthentication(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        String requestApiKey = resolveApiKey(exchange.getRequest());
        if (configuredApiKey.equals(requestApiKey)) {
            return chain.filter(exchange);
        }

        byte[] payload = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
    }

    private boolean requiresAuthentication(String path) {
        return "/sse".equals(path) || path.startsWith("/mcp/");
    }

    private String resolveApiKey(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return request.getHeaders().getFirst(API_KEY_HEADER);
    }
}