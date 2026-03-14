package dev.dancherbu.ccm.config;

import dev.dancherbu.ccm.mcp.McpUsageTelemetryService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class McpUsageTelemetryWebFilter implements WebFilter {

    private final McpUsageTelemetryService usageTelemetryService;

    public McpUsageTelemetryWebFilter(McpUsageTelemetryService usageTelemetryService) {
        this.usageTelemetryService = usageTelemetryService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if ("/sse".equals(path)) {
            usageTelemetryService.recordSseOpen();
        } else if ("/mcp/message".equals(path)) {
            String sessionId = exchange.getRequest().getQueryParams().getFirst("sessionId");
            usageTelemetryService.recordMcpMessage(sessionId);
        }
        return chain.filter(exchange);
    }
}