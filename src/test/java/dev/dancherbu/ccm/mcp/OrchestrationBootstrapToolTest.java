package dev.dancherbu.ccm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.nullable;

import dev.dancherbu.ccm.model.ContextBundle;
import dev.dancherbu.ccm.model.OrchestrationBootstrap;
import dev.dancherbu.ccm.model.OrchestrationPlan;
import dev.dancherbu.ccm.model.ProjectReadinessReport;
import dev.dancherbu.ccm.model.ProjectReadinessStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestrationBootstrapToolTest {

    @Test
    void returnsReadinessAndPlanWhenNoReadyProject() {
        ProjectReadinessGuardService readinessGuardService = org.mockito.Mockito.mock(ProjectReadinessGuardService.class);
        OrchestrationPlanTool orchestrationPlanTool = org.mockito.Mockito.mock(OrchestrationPlanTool.class);
        BuildContextBundleTool buildContextBundleTool = org.mockito.Mockito.mock(BuildContextBundleTool.class);
        OrchestrationSessionService orchestrationSessionService = new OrchestrationSessionService();

        when(readinessGuardService.readiness(anyString())).thenReturn(readiness(false));
        when(orchestrationPlanTool.get_orchestration_plan(anyString(), any())).thenReturn(plan(false));

        OrchestrationBootstrapTool tool = new OrchestrationBootstrapTool(
                readinessGuardService,
                orchestrationPlanTool,
                buildContextBundleTool,
                orchestrationSessionService);

        OrchestrationBootstrap result = tool.get_orchestration_bootstrap(
                "map the server architecture",
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(result.projectReadiness().hasReadyProject()).isFalse();
        assertThat(result.orchestrationSessionId()).isNotBlank();
        assertThat(result.sessionState()).containsEntry("known", true);
        assertThat(result.initialContextBundle()).isNull();
        assertThat(result.initialContextBundleStatus()).contains("No ready project");
        verify(buildContextBundleTool, never()).build_context_bundle(
                anyString(),
                anyInt(),
                anyInt(),
                anyString(),
                nullable(String.class),
                any(),
                nullable(String.class));
    }

    @Test
    void returnsInitialBundleWhenProjectIsReady() {
        ProjectReadinessGuardService readinessGuardService = org.mockito.Mockito.mock(ProjectReadinessGuardService.class);
        OrchestrationPlanTool orchestrationPlanTool = org.mockito.Mockito.mock(OrchestrationPlanTool.class);
        BuildContextBundleTool buildContextBundleTool = org.mockito.Mockito.mock(BuildContextBundleTool.class);
        OrchestrationSessionService orchestrationSessionService = new OrchestrationSessionService();

        when(readinessGuardService.readiness(anyString())).thenReturn(readiness(true));
        when(orchestrationPlanTool.get_orchestration_plan(anyString(), any())).thenReturn(plan(true));
        when(buildContextBundleTool.build_context_bundle(anyString(), anyInt(), anyInt(), anyString(), nullable(String.class), any(), nullable(String.class)))
                .thenReturn(new ContextBundle(
                        "map the server architecture",
                        "/workspace/projects/repo",
                        "bundle",
                        List.of("entry"),
                        List.of("support"),
                        List.of(),
                        "lean",
                        120,
                        List.of("within budget"),
                        "ctx-hash",
                        false,
                        false,
                        0,
                        "live-index"));

        OrchestrationBootstrapTool tool = new OrchestrationBootstrapTool(
                readinessGuardService,
                orchestrationPlanTool,
                buildContextBundleTool,
                orchestrationSessionService);

        OrchestrationBootstrap result = tool.get_orchestration_bootstrap(
                "map the server architecture",
                "",
                null,
                null,
                null,
                1800,
                null,
                null,
                null);

        assertThat(result.projectReadiness().hasReadyProject()).isTrue();
        assertThat(result.orchestrationSessionId()).isNotBlank();
        assertThat(result.sessionState()).containsEntry("known", true);
        assertThat(result.initialContextBundle()).isNotNull();
        assertThat(result.initialContextBundleStatus()).contains("generated");
        verify(buildContextBundleTool).build_context_bundle(
                anyString(),
                anyInt(),
                anyInt(),
                anyString(),
                nullable(String.class),
                any(),
                nullable(String.class));
    }

    @Test
    void omitsUnchangedBundlePayloadWhenRequested() {
        ProjectReadinessGuardService readinessGuardService = org.mockito.Mockito.mock(ProjectReadinessGuardService.class);
        OrchestrationPlanTool orchestrationPlanTool = org.mockito.Mockito.mock(OrchestrationPlanTool.class);
        BuildContextBundleTool buildContextBundleTool = org.mockito.Mockito.mock(BuildContextBundleTool.class);
        OrchestrationSessionService orchestrationSessionService = new OrchestrationSessionService();

        when(readinessGuardService.readiness(anyString())).thenReturn(readiness(true));
        when(orchestrationPlanTool.get_orchestration_plan(anyString(), any())).thenReturn(plan(true));
        when(buildContextBundleTool.build_context_bundle(anyString(), anyInt(), anyInt(), anyString(), nullable(String.class), any(), nullable(String.class)))
                .thenReturn(new ContextBundle(
                        "map the server architecture",
                        "/workspace/projects/repo",
                        "bundle",
                        List.of(),
                        List.of(),
                        List.of(),
                        "lean",
                        1,
                        List.of("unchanged"),
                        "ctx-hash",
                        true,
                        true,
                        0,
                        "context-hash"));

        OrchestrationBootstrapTool tool = new OrchestrationBootstrapTool(
                readinessGuardService,
                orchestrationPlanTool,
                buildContextBundleTool,
                orchestrationSessionService);

        OrchestrationBootstrap result = tool.get_orchestration_bootstrap(
                "map the server architecture",
                "",
                null,
                null,
                null,
                1800,
                null,
                null,
                false);

        assertThat(result.initialContextBundle()).isNull();
        assertThat(result.initialContextBundleStatus()).contains("payload omitted");
    }

    private ProjectReadinessReport readiness(boolean hasReadyProject) {
        List<String> readyProjects = hasReadyProject ? List.of("sample-project") : List.of();
        List<String> blockedProjects = hasReadyProject ? List.of() : List.of("sample-project");
        List<ProjectReadinessStatus> statuses = List.of(new ProjectReadinessStatus(
                "sample-project",
                "/workspace/projects/repo",
                hasReadyProject,
                hasReadyProject ? 90.0 : 10.0,
                hasReadyProject ? 90.0 : 10.0,
                hasReadyProject ? 90.0 : 10.0,
                80.0,
                List.of(),
                List.of("src/main/java/App.java")));
        return new ProjectReadinessReport(
                "2026-03-14T00:00:00Z",
                "",
                80.0,
                80.0,
                80.0,
                80.0,
                hasReadyProject,
                hasReadyProject ? "sample-project" : "",
                hasReadyProject ? "lean" : "verbose",
                hasReadyProject ? 6 : 3,
                hasReadyProject ? 4 : 2,
                hasReadyProject ? List.of() : List.of("Coverage below threshold"),
                readyProjects,
                blockedProjects,
                statuses,
                List.of());
    }

    private OrchestrationPlan plan(boolean hasReadyProject) {
        return new OrchestrationPlan(
                "2026-03-14T00:00:00Z",
                hasReadyProject,
                hasReadyProject ? "sample-project" : "",
                hasReadyProject ? "lean" : "verbose",
                hasReadyProject ? 6 : 3,
                hasReadyProject ? 4 : 2,
                1800,
                hasReadyProject ? List.of() : List.of("Coverage below threshold"),
                Map.of("build_context_bundle", Map.of("query", "<your-change-request>")),
                List.of("Call build_context_bundle"));
    }
}