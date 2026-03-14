package dev.dancherbu.ccm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.cache.SemanticCacheService;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.FileImpact;
import dev.dancherbu.ccm.model.ImpactAnalysisResult;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.support.TokenBudgetService;
import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

class AnalyzeImpactedFilesToolTest {

    @Test
    void fallsBackToDeterministicVectorReasoningWhenChatModelFails() {
        CodebaseVectorStore vectorStore = mock(CodebaseVectorStore.class);
        SemanticCacheService cacheService = mock(SemanticCacheService.class);
        ChatClient chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        TokenBudgetService tokenBudgetService = mock(TokenBudgetService.class);
        ProjectReadinessGuardService readinessGuardService = mock(ProjectReadinessGuardService.class);

        CcmProperties properties = new CcmProperties();
        properties.setMaxSnippetCharacters(400);
        properties.setAnalysisTokenLimit(2000);
        properties.getOllama().setChatModel("missing-model");

        Document applicationConfig = Document.builder()
                .id("1")
                .text("class ApplicationConfig { ChatClient chatClient(ChatModel chatModel) { return ChatClient.builder(chatModel).build(); } }")
                .metadata(Map.of(
                        "filePath", "/workspace/projects/repo/src/main/java/dev/dancherbu/ccm/config/ApplicationConfig.java",
                        "symbolName", "ApplicationConfig",
                        "node_type", "class_declaration",
                        "startLine", 12,
                        "endLine", 48))
                .build();
        Document analyzeTool = Document.builder()
                .id("2")
                .text("class AnalyzeImpactedFilesTool { List<FileImpact> analyze_impacted_files(String query, Integer topK) { return List.of(); } }")
                .metadata(Map.of(
                        "filePath", "/workspace/projects/repo/src/main/java/dev/dancherbu/ccm/mcp/AnalyzeImpactedFilesTool.java",
                        "symbolName", "analyze_impacted_files",
                        "node_type", "method_declaration",
                        "startLine", 20,
                        "endLine", 88))
                .build();

        when(cacheService.getImpacts(anyString())).thenReturn(null);
        when(readinessGuardService.requireReadyProject(anyString(), anyString()))
                .thenReturn(new IndexCoverageProject(
                        "sample-project",
                        "/workspace/projects/repo",
                        85.0,
                        true,
                        0.0,
                        85.0,
                        true,
                        0.0,
                        85.0,
                        true,
                        0.0,
                        85.0,
                        true,
                        0.0,
                        List.of(),
                        4,
                        4,
                        100.0,
                        8,
                        8,
                        100.0,
                        2,
                        2,
                        100.0,
                        "",
                        List.of(),
                        List.of()));
        when(vectorStore.searchInProject(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(List.of(applicationConfig, analyzeTool));
        when(chatClient.prompt(anyString()).call().content()).thenThrow(new RuntimeException("model not found"));
        when(tokenBudgetService.exceeds(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);

        AnalyzeImpactedFilesTool tool = new AnalyzeImpactedFilesTool(
                vectorStore,
                cacheService,
                chatClient,
                tokenBudgetService,
                new ObjectMapper(),
                properties,
                readinessGuardService);

        ImpactAnalysisResult result = tool.analyze_impacted_files(
                "add a context bundle tool",
                5,
                "sample-project",
                "verbose",
                2000,
                null);
        List<FileImpact> impacts = result.impacts();

        assertThat(impacts).hasSize(2);
        assertThat(impacts.getFirst().reasonForEdit()).contains("Vector match fallback");
        assertThat(impacts.getFirst().reasonForEdit()).contains("add a context bundle tool");
    }
}