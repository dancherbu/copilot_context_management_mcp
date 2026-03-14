package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.IndexCoverageSnapshot;
import dev.dancherbu.ccm.model.McpUsageSnapshot;
import dev.dancherbu.ccm.model.MetricsBenchRequest;
import dev.dancherbu.ccm.model.MetricsBenchResponse;
import dev.dancherbu.ccm.model.MetricsBenchRun;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
public class McpMetricsController {

    private final McpMetricsBenchmarkService benchmarkService;
    private final MetricsHistoryService metricsHistoryService;
    private final IndexInsightsService indexInsightsService;
    private final McpUsageTelemetryService usageTelemetryService;
    private final CcmProperties properties;

    public McpMetricsController(
            McpMetricsBenchmarkService benchmarkService,
            MetricsHistoryService metricsHistoryService,
            IndexInsightsService indexInsightsService,
            McpUsageTelemetryService usageTelemetryService,
            CcmProperties properties) {
        this.benchmarkService = benchmarkService;
        this.metricsHistoryService = metricsHistoryService;
        this.indexInsightsService = indexInsightsService;
        this.usageTelemetryService = usageTelemetryService;
        this.properties = properties;
    }

    @PostMapping("/bench")
    public MetricsBenchResponse benchmark(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey,
            @RequestBody MetricsBenchRequest request) throws Exception {
        authorize(resolveApiKey(authorization, headerApiKey, request.apiKey()));
        return benchmarkService.benchmark(request);
    }

    @GetMapping("/history")
    public List<MetricsBenchRun> history(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey) {
        authorize(resolveApiKey(authorization, headerApiKey, null));
        return metricsHistoryService.getRecentRuns();
    }

    @PostMapping("/history/reset")
    public List<MetricsBenchRun> resetHistory(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey) {
        authorize(resolveApiKey(authorization, headerApiKey, null));
        return metricsHistoryService.resetRuns();
    }

    @GetMapping("/index-overview")
    public IndexCoverageSnapshot indexOverview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey) {
        authorize(resolveApiKey(authorization, headerApiKey, null));
        return indexInsightsService.snapshot();
    }

    @GetMapping("/usage")
    public McpUsageSnapshot usage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey) {
        authorize(resolveApiKey(authorization, headerApiKey, null));
        return usageTelemetryService.snapshot();
    }

    @PostMapping("/usage/reset")
    public McpUsageSnapshot resetUsage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-CCM-API-Key", required = false) String headerApiKey) {
        authorize(resolveApiKey(authorization, headerApiKey, null));
        return usageTelemetryService.reset();
    }

    private void authorize(String apiKey) {
        String configuredKey = properties.getSecurity().getApiKey();
        if (configuredKey != null && !configuredKey.isBlank() && !configuredKey.equals(apiKey)) {
            throw new MetricsUnauthorizedException();
        }
    }

    private String resolveApiKey(String authorization, String headerApiKey, String bodyApiKey) {
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        if (StringUtils.hasText(headerApiKey)) {
            return headerApiKey;
        }
        return bodyApiKey;
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    private static class MetricsUnauthorizedException extends RuntimeException {
    }
}