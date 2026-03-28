package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ToolCatalog;
import java.time.Instant;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalogTool {

        private static final String DEFAULT_SERVER_NAME = "SpringAI-Coder-Bench";

    private static final List<String> TOOL_NAMES = List.of(
            "analyze_impacted_files",
            "build_context_bundle",
            "trace_change_impact",
            "find_test_obligations",
            "assemble_execution_brief",
            "find_similar_implementations",
            "get_project_readiness",
            "get_project_guidance",
            "get_orchestration_plan",
            "get_orchestration_bootstrap",
            "get_tool_catalog");

    @McpTool(
            name = "get_tool_catalog",
            description = "Return the MCP tool names and fully-qualified Copilot agent tool IDs for this server.")
    public ToolCatalog get_tool_catalog(
            @McpToolParam(
                            description = "Optional MCP server name prefix used by Copilot agent tool IDs. Defaults to 'SpringAI-Coder-Bench'.",
                            required = false)
                    String serverName) {
        String resolvedServerName = (serverName == null || serverName.isBlank())
                ? DEFAULT_SERVER_NAME
                : serverName.trim();

        List<String> agentToolIds = TOOL_NAMES.stream()
                .map(toolName -> resolvedServerName + "/" + toolName)
                .toList();

        return new ToolCatalog(
                Instant.now().toString(),
                resolvedServerName,
                TOOL_NAMES,
                agentToolIds,
                List.of(
                        "Use 'agentToolIds' values directly in custom agent tools arrays.",
                        "If your server is registered under a different name in mcp.json, pass that name here.",
                        "This output is deterministic and can be cached by clients."));
    }
}
