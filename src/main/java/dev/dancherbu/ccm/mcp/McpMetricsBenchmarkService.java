package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.model.MetricsBenchRequest;
import dev.dancherbu.ccm.model.MetricsBenchResponse;
import dev.dancherbu.ccm.model.MetricsBenchResult;
import dev.dancherbu.ccm.model.MetricsBenchRun;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class McpMetricsBenchmarkService {

    private static final Set<String> PROJECT_GATED_TOOLS = Set.of(
            "analyze_impacted_files",
            "build_context_bundle",
            "trace_change_impact",
            "find_test_obligations",
            "assemble_execution_brief",
            "find_similar_implementations");

    private final ObjectMapper objectMapper;
    private final WebServerApplicationContext applicationContext;
    private final MetricsHistoryService metricsHistoryService;

    public McpMetricsBenchmarkService(
            ObjectMapper objectMapper,
            WebServerApplicationContext applicationContext,
            MetricsHistoryService metricsHistoryService) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.metricsHistoryService = metricsHistoryService;
    }

    public MetricsBenchResponse benchmark(MetricsBenchRequest request) throws Exception {
        boolean requiresProject = request.tools().stream().anyMatch(PROJECT_GATED_TOOLS::contains);
        if (requiresProject && (request.projectName() == null || request.projectName().isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "projectName is required for readiness-gated tools. Call get_project_readiness first.");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        long initStarted = System.nanoTime();
        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/sse"))
                .header("X-CCM-API-Key", request.apiKey())
                .GET()
                .build();
        HttpResponse<InputStream> sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (sseResponse.statusCode() != 200) {
            throw new IllegalStateException("Unable to open SSE session: HTTP " + sseResponse.statusCode());
        }

        try (InputStream inputStream = sseResponse.body();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            SseEvent endpointEvent = readEvent(reader);
            String endpoint = endpointEvent.data();

            post(client, request.apiKey(), endpoint, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ccm-metrics-bench","version":"1.0.0"}}}
                    """);
            waitForId(reader, 1);
            post(client, request.apiKey(), endpoint, """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """);
            long sessionInitMs = elapsedMs(initStarted);

            List<MetricsBenchResult> results = new ArrayList<>();
            int requestId = 100;
            for (String toolName : request.tools()) {
                long started = System.nanoTime();
                post(client, request.apiKey(), endpoint, toolCallPayload(requestId, toolName, request.query(), request.projectName()));
                JsonNode response = waitForId(reader, requestId);
                long roundtripMs = elapsedMs(started);
                String text = response.path("result").path("content").path(0).path("text").asText("");
                JsonNode parsed = parseJson(text);
                List<String> keys = new ArrayList<>();
                if (parsed != null && parsed.isObject()) {
                    parsed.fieldNames().forEachRemaining(keys::add);
                }
                int estimatedPayloadTokens = parsed == null ? 0 : parsed.path("estimatedPayloadTokens").asInt(0);
                int responseBytes = text.getBytes(StandardCharsets.UTF_8).length;
                String preview = text.length() <= 1200 ? text : text.substring(0, 1200) + "...";
                String status = response.path("result").path("isError").asBoolean(false) ? "error" : "ok";
                results.add(new MetricsBenchResult(toolName, roundtripMs, estimatedPayloadTokens, responseBytes, status, keys, preview));
                requestId++;
            }

            int totalEstimatedPayloadTokens = results.stream().mapToInt(MetricsBenchResult::estimatedPayloadTokens).sum();
            int errorCount = (int) results.stream().filter(result -> !"ok".equals(result.status())).count();
            long medianRoundtripMs = median(results.stream().map(MetricsBenchResult::roundtripMs).toList());
                MetricsBenchResponse response = new MetricsBenchResponse(
                    sessionInitMs,
                    results.size(),
                    medianRoundtripMs,
                    totalEstimatedPayloadTokens,
                    errorCount,
                    results,
                    List.of());
                List<MetricsBenchRun> recentRuns = metricsHistoryService.recordRun(request.query(), request.tools(), response);
                return new MetricsBenchResponse(
                    response.sessionInitMs(),
                    response.toolsRun(),
                    response.medianRoundtripMs(),
                    response.totalEstimatedPayloadTokens(),
                    response.errorCount(),
                    response.results(),
                    recentRuns);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + applicationContext.getWebServer().getPort();
    }

    private void post(HttpClient client, String apiKey, String endpoint, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + endpoint))
                .header("Content-Type", "application/json")
                .header("X-CCM-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("MCP POST failed with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private JsonNode waitForId(BufferedReader reader, int requestId) throws Exception {
        while (true) {
            SseEvent event = readEvent(reader);
            if (!"message".equals(event.event())) {
                continue;
            }
            JsonNode payload = objectMapper.readTree(event.data());
            if (payload.path("id").asInt(-1) == requestId) {
                return payload;
            }
        }
    }

    private SseEvent readEvent(BufferedReader reader) throws Exception {
        String event = null;
        String data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                if (event != null || data != null) {
                    return new SseEvent(event == null ? "message" : event, data == null ? "" : data);
                }
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                data = data == null ? value : data + "\n" + value;
            }
        }
        throw new IllegalStateException("SSE stream closed before the expected event was received");
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000_000.0);
    }

    private long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
        }
        return sorted.get(middle);
    }

        private String toolCallPayload(int id, String toolName, String query, String projectName) throws Exception {
        Object arguments = switch (toolName) {
            case "trace_change_impact", "find_test_obligations", "assemble_execution_brief" ->
                    objectMapper
                    .createObjectNode()
                    .put("query", query)
                    .put("topK", 6)
                    .put("maxFiles", 4)
                    .put("responseMode", "lean")
                    .put("tokenBudget", 1800)
                        .put("projectName", projectName);
            case "build_context_bundle" ->
                    objectMapper
                    .createObjectNode()
                    .put("query", query)
                    .put("topK", 6)
                    .put("maxFiles", 4)
                    .put("responseMode", "lean")
                    .put("tokenBudget", 1800)
                        .put("projectName", projectName);
            case "find_similar_implementations" ->
                objectMapper
                    .createObjectNode()
                    .put("query", query)
                    .put("topK", 6)
                    .put("maxExamples", 4)
                    .put("responseMode", "lean")
                    .put("tokenBudget", 1800)
                        .put("projectName", projectName);
            case "analyze_impacted_files" -> objectMapper
                .createObjectNode()
                .put("query", query)
                .put("topK", 6)
                .put("responseMode", "lean")
                .put("tokenBudget", 1800)
                    .put("projectName", projectName);
                case "get_project_readiness" -> objectMapper.createObjectNode().put("projectName", projectName == null ? "" : projectName);
            default -> throw new IllegalArgumentException("Unsupported tool for metrics bench: " + toolName);
        };

        return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .set("params", objectMapper.createObjectNode()
                        .put("name", toolName)
                        .set("arguments", (JsonNode) arguments)));
    }

    private record SseEvent(String event, String data) {
    }
}