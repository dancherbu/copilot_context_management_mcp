package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.ProjectReadinessReport;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class ProjectReadinessTool {

    private final ProjectReadinessGuardService readinessGuardService;

    public ProjectReadinessTool(ProjectReadinessGuardService readinessGuardService) {
        this.readinessGuardService = readinessGuardService;
    }

    @McpTool(
            name = "get_project_readiness",
            description = "Return readiness status for indexed projects so clients can pick projects eligible for analysis tools.")
    public ProjectReadinessReport get_project_readiness(
            @McpToolParam(description = "Optional project name filter", required = false) String projectName) {
        return readinessGuardService.readiness(projectName);
    }
}