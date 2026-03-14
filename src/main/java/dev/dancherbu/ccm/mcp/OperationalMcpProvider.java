package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.config.CcmProperties;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Service;

@Service
public class OperationalMcpProvider {

    private final CcmProperties properties;

    public OperationalMcpProvider(CcmProperties properties) {
        this.properties = properties;
    }

    @McpResource(uri = "logs://application", mimeType = "text/plain", name = "Application log")
    public String applicationLog() throws IOException {
        Path logPath = Path.of(System.getProperty("LOG_FILE_PATH", "logs/application.log"));
        if (!Files.exists(logPath)) {
            return "Log file does not exist yet: " + logPath;
        }
        List<String> lines = Files.readAllLines(logPath);
        int fromIndex = Math.max(0, lines.size() - 200);
        return String.join(System.lineSeparator(), lines.subList(fromIndex, lines.size()));
    }

    @McpPrompt(name = "code-review", description = "Generate a local code review prompt using the indexed repository context")
    public GetPromptResult codeReviewPrompt(
            @McpArg(name = "changeSummary", description = "Summary of the requested or proposed code change", required = true)
                    String changeSummary) {
        String prompt = "Review the requested change with focus on regressions, impacted files, test gaps, and operational risk. "
                + "Repository root: " + properties.getWatchRoot() + System.lineSeparator()
                + "Requested change: " + changeSummary;
        return new GetPromptResult(
                "Code Review",
                List.of(new PromptMessage(Role.USER, new TextContent(prompt))));
    }
}
