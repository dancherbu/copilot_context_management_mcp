package dev.dancherbu.ccm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ccm.watch-root=/tmp/ccm-mcp-test-watch-root-missing",
            "ccm.vector-store-file=/tmp/ccm-mcp-test-vector-store.json",
                        "ccm.metrics.history-file=/tmp/ccm-mcp-test-metrics-history.json",
            "ccm.security.api-key=test-key"
        })
class McpServerIntegrationTest {

        private static final Path WATCH_ROOT = Path.of("/tmp/ccm-mcp-test-watch-root-missing");
        private static final Path HISTORY_FILE = Path.of("/tmp/ccm-mcp-test-metrics-history.json");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

        @Autowired
        private IndexInsightsService indexInsightsService;

    @MockBean
    private CodebaseVectorStore codebaseVectorStore;

    @MockBean
    private ChatClient chatClient;

    @MockBean
    private ChatModel chatModel;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private InputStream sseStream;

    @AfterEach
    void cleanup() throws Exception {
        if (sseStream != null) {
            sseStream.close();
        }
                indexInsightsService.invalidate();
                Files.deleteIfExists(HISTORY_FILE);
    }

    @Test
    void servesToolsAndBuildsContextBundleOverMcpSse() throws Exception {
        Document appClass = document(
                "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/CopilotContextManagementMcpApplication.java",
                "CopilotContextManagementMcpApplication",
                "class_declaration",
                1,
                30,
                "public class CopilotContextManagementMcpApplication { }",
                "java");
        Document appConfig = document(
                "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/config/ApplicationConfig.java",
                "ApplicationConfig",
                "class_declaration",
                10,
                44,
                "class ApplicationConfig { ChatClient chatClient(ChatModel chatModel) { return ChatClient.builder(chatModel).build(); } }",
                "java");
        Document appConfigTest = document(
                "/workspace/projects/copilot_context_management_mcp/src/test/java/dev/dancherbu/ccm/config/ApplicationConfigTest.java",
                "ApplicationConfigTest",
                "class_declaration",
                1,
                20,
                "class ApplicationConfigTest { void verifiesChatClient() { new ApplicationConfig(); } }",
                "java");
        when(codebaseVectorStore.search(eq("map the MCP server structure"), anyInt()))
                .thenReturn(List.of(appClass, appConfig));
        when(codebaseVectorStore.search(eq("existing MCP planning patterns"), anyInt()))
                .thenReturn(List.of(appConfig, appClass));
        when(codebaseVectorStore.getKnownFiles())
                .thenReturn(List.of(
                        "/workspace/projects/copilot_context_management_mcp/pom.xml",
                        "/workspace/projects/copilot_context_management_mcp/src/main/resources/application.yml",
                        "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/CopilotContextManagementMcpApplication.java",
                        "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/config/ApplicationConfig.java",
                        "/workspace/projects/copilot_context_management_mcp/src/test/java/dev/dancherbu/ccm/config/ApplicationConfigTest.java"));
        when(codebaseVectorStore.getAllDocuments()).thenReturn(List.of(appClass, appConfig, appConfigTest));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/sse"))
                .header("X-CCM-API-Key", "test-key")
                .GET()
                .build();

        HttpResponse<InputStream> sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(sseResponse.statusCode()).isEqualTo(200);
        sseStream = sseResponse.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(sseStream, StandardCharsets.UTF_8));

        SseEvent endpointEvent = readEvent(reader);
        assertThat(endpointEvent.event()).isEqualTo("endpoint");
        assertThat(endpointEvent.data()).startsWith("/mcp/message?sessionId=");

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}
                """);
        JsonNode initialize = objectMapper.readTree(readEvent(reader).data());
        assertThat(initialize.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("copilot-context-management-mcp");

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        JsonNode tools = objectMapper.readTree(readEvent(reader).data());
        String toolsJson = tools.path("result").path("tools").toString();
        assertThat(toolsJson).contains("analyze_impacted_files");
        assertThat(toolsJson).contains("build_context_bundle");
        assertThat(toolsJson).contains("trace_change_impact");
        assertThat(toolsJson).contains("find_test_obligations");
        assertThat(toolsJson).contains("assemble_execution_brief");
        assertThat(toolsJson).contains("find_similar_implementations");
        assertThat(toolsJson).contains("get_project_readiness");
        assertThat(toolsJson).contains("get_orchestration_plan");
        assertThat(toolsJson).contains("get_orchestration_bootstrap");

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"get_project_readiness","arguments":{}}}
                """);
        JsonNode readinessResult = objectMapper.readTree(readEvent(reader).data());
        assertThat(readinessResult.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(readinessResult.path("result").path("content").get(0).path("text").asText())
                .contains("projects");

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"get_orchestration_bootstrap","arguments":{"query":"map the MCP server structure"}}}
                """);
        JsonNode bootstrapResult = objectMapper.readTree(readEvent(reader).data());
        assertThat(bootstrapResult.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(bootstrapResult.path("result").path("content").get(0).path("text").asText())
                .contains("projectReadiness")
                .contains("orchestrationPlan")
                .contains("initialContextBundleStatus")
                .contains("\"hasReadyProject\":false")
                .contains("\"initialContextBundle\":null")
                .contains("No ready project yet");

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"build_context_bundle","arguments":{"query":"map the MCP server structure","topK":5,"maxFiles":4,"projectName":"copilot_context_management_mcp"}}}
                """);
        JsonNode bundleResult = objectMapper.readTree(readEvent(reader).data());
        assertThat(bundleResult.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(bundleResult.path("result").path("content").get(0).path("text").asText())
                .contains("projectName");

        post(client, endpointEvent.data(), """
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"assemble_execution_brief","arguments":{"query":"map the MCP server structure","topK":5,"maxFiles":4,"projectName":"copilot_context_management_mcp"}}}
            """);
        JsonNode brief = objectMapper.readTree(readEvent(reader).data());
        assertThat(brief.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(brief.path("result").path("content").get(0).path("text").asText())
                .contains("projectName");

                post(client, endpointEvent.data(), """
                        {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_similar_implementations","arguments":{"query":"existing MCP planning patterns","topK":5,"maxExamples":3,"projectName":"copilot_context_management_mcp"}}}
                        """);
                JsonNode similar = objectMapper.readTree(readEvent(reader).data());
                assertThat(similar.path("result").path("isError").asBoolean(false)).isTrue();
                assertThat(similar.path("result").path("content").get(0).path("text").asText())
                                .contains("projectName");
    }

        @Test
        void servesMetricsUiPage() throws Exception {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp-metrics.html"))
                                .GET()
                                .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("CCM MCP Metrics Bench");
                assertThat(response.body()).contains("Run Selected Tools");
                assertThat(response.body()).contains("Embedding Coverage");
                assertThat(response.body()).contains("Preset Suite");
                assertThat(response.body()).contains("Final Client Payload");
                assertThat(response.body()).contains("const DEFAULT_API_KEY = \"test-key\";");
        }

            @Test
            void servesMetricsBenchApiAndHistory() throws Exception {
                Document appClass = document(
                        "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/CopilotContextManagementMcpApplication.java",
                        "CopilotContextManagementMcpApplication",
                        "class_declaration",
                        1,
                        30,
                        "public class CopilotContextManagementMcpApplication { }",
                        "java");
                Document appConfig = document(
                        "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/config/ApplicationConfig.java",
                        "ApplicationConfig",
                        "class_declaration",
                        10,
                        44,
                        "class ApplicationConfig { ChatClient chatClient(ChatModel chatModel) { return ChatClient.builder(chatModel).build(); } }",
                        "java");
                Document appConfigTest = document(
                        "/workspace/projects/copilot_context_management_mcp/src/test/java/dev/dancherbu/ccm/config/ApplicationConfigTest.java",
                        "ApplicationConfigTest",
                        "class_declaration",
                        1,
                        20,
                        "class ApplicationConfigTest { void verifiesChatClient() { new ApplicationConfig(); } }",
                        "java");
                when(codebaseVectorStore.search(eq("bench the planning tools"), anyInt()))
                        .thenReturn(List.of(appClass, appConfig));
                when(codebaseVectorStore.getKnownFiles())
                        .thenReturn(List.of(
                                "/workspace/projects/copilot_context_management_mcp/pom.xml",
                                "/workspace/projects/copilot_context_management_mcp/src/main/resources/application.yml",
                                "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/CopilotContextManagementMcpApplication.java",
                                "/workspace/projects/copilot_context_management_mcp/src/main/java/dev/dancherbu/ccm/config/ApplicationConfig.java",
                                "/workspace/projects/copilot_context_management_mcp/src/test/java/dev/dancherbu/ccm/config/ApplicationConfigTest.java"));
                when(codebaseVectorStore.getAllDocuments()).thenReturn(List.of(appClass, appConfig, appConfigTest));

                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/metrics/bench"))
                        .header("Content-Type", "application/json")
                        .header("X-CCM-API-Key", "test-key")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"apiKey":"test-key","query":"bench the planning tools","projectName":"copilot_context_management_mcp","tools":["trace_change_impact","find_test_obligations","assemble_execution_brief"]}
                                """))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode payload = objectMapper.readTree(response.body());
                assertThat(payload.path("toolsRun").asInt()).isEqualTo(3);
                assertThat(payload.path("results").toString()).contains("trace_change_impact");
                assertThat(payload.path("results").toString()).contains("estimatedPayloadTokens");
                assertThat(payload.path("recentRuns").isArray()).isTrue();
                assertThat(payload.path("recentRuns").get(0).path("query").asText()).isEqualTo("bench the planning tools");

                HttpRequest historyRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/metrics/history"))
                        .header("X-CCM-API-Key", "test-key")
                        .GET()
                        .build();
                HttpResponse<String> historyResponse = client.send(historyRequest, HttpResponse.BodyHandlers.ofString());
                assertThat(historyResponse.statusCode()).isEqualTo(200);
                assertThat(historyResponse.body()).contains("bench the planning tools");
            }

                        @Test
                        void rejectsMetricsBenchWhenProjectNameIsMissingForGatedTool() throws Exception {
                                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/metrics/bench"))
                                                .header("Content-Type", "application/json")
                                                .header("X-CCM-API-Key", "test-key")
                                                .POST(HttpRequest.BodyPublishers.ofString("""
                                                                {"apiKey":"test-key","query":"bench gated tool","tools":["trace_change_impact"]}
                                                                """))
                                                .build();

                                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                                assertThat(response.statusCode()).isEqualTo(400);
                        }

            @Test
            void servesIndexOverviewApi() throws Exception {
                Path projectDir = WATCH_ROOT.resolve("sample-project/src/main/java/example");
                Files.createDirectories(projectDir);
                Path javaFile = projectDir.resolve("SampleService.java");
                Files.writeString(
                        javaFile,
                        """
                        package example;

                        public class SampleService {
                            public void firstMethod() {
                            }

                            public void secondMethod() {
                            }
                        }
                        """);

                Document classDoc = document(
                        javaFile.toString(),
                        "SampleService",
                        "class_declaration",
                        3,
                        9,
                        "public class SampleService { }",
                        "java");
                Document methodDoc = document(
                        javaFile.toString(),
                        "firstMethod",
                        "method_declaration",
                        4,
                        5,
                        "public void firstMethod() { }",
                        "java");
                when(codebaseVectorStore.getAllDocuments()).thenReturn(List.of(classDoc, methodDoc));

                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/metrics/index-overview"))
                        .header("X-CCM-API-Key", "test-key")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode payload = objectMapper.readTree(response.body());
                assertThat(payload.path("totalProjects").asInt()).isEqualTo(1);
                                assertThat(payload.path("embeddingCoverageThresholdPercent").asDouble()).isEqualTo(85.0);
                                assertThat(payload.path("embeddingCoverageThresholdMet").asBoolean()).isFalse();
                                assertThat(payload.path("fileCoverageThresholdPercent").asDouble()).isEqualTo(85.0);
                                assertThat(payload.path("chunkCoverageThresholdPercent").asDouble()).isEqualTo(85.0);
                                assertThat(payload.path("semanticCoverageThresholdPercent").asDouble()).isEqualTo(85.0);
                                assertThat(payload.path("warnings").isArray()).isTrue();
                assertThat(payload.path("projects").toString()).contains("sample-project");
                                assertThat(payload.path("projects").get(0).path("warnings").isArray()).isTrue();
            }

    @Test
    void blocksToolCallWhenProjectCoverageIsBelowThreshold() throws Exception {
        Path projectDir = WATCH_ROOT.resolve("sample-project/src/main/java/example");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("SampleService.java"), "package example; class SampleService {}\n");
        Document unrelatedDoc = document(
                "/workspace/projects/another-project/src/main/java/example/Other.java",
                "Other",
                "class_declaration",
                1,
                2,
                "class Other {}",
                "java");
        when(codebaseVectorStore.getAllDocuments()).thenReturn(List.of(unrelatedDoc));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/sse"))
                .header("X-CCM-API-Key", "test-key")
                .GET()
                .build();

        HttpResponse<InputStream> sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(sseResponse.statusCode()).isEqualTo(200);
        sseStream = sseResponse.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(sseStream, StandardCharsets.UTF_8));

        SseEvent endpointEvent = readEvent(reader);
        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}
                """);
        readEvent(reader);
        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);

        post(client, endpointEvent.data(), """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"build_context_bundle","arguments":{"query":"map server","topK":5,"maxFiles":4,"projectName":"sample-project"}}}
                """);
        JsonNode blocked = objectMapper.readTree(readEvent(reader).data());

        assertThat(blocked.path("result").path("isError").asBoolean()).isTrue();
        assertThat(blocked.path("result").path("content").get(0).path("text").asText())
                .contains("blocked for project 'sample-project'")
                .contains("below readiness threshold");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void post(HttpClient client, String endpoint, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + endpoint))
                .header("Content-Type", "application/json")
                .header("X-CCM-API-Key", "test-key")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
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
        throw new IllegalStateException("SSE stream closed before expected event was received");
    }

    private Document document(
            String filePath,
            String symbolName,
            String nodeType,
            int startLine,
            int endLine,
            String snippet,
            String language) {
        return Document.builder()
                .id(filePath + ':' + startLine)
                .text(snippet)
                .metadata(Map.of(
                        "filePath", filePath,
                        "symbolName", symbolName,
                        "node_type", nodeType,
                        "startLine", startLine,
                        "endLine", endLine,
                        "language", language))
                .build();
    }

    private record SseEvent(String event, String data) {
    }
}