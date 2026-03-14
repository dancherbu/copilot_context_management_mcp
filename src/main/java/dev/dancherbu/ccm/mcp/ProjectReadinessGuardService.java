package dev.dancherbu.ccm.mcp;

import dev.dancherbu.ccm.model.IndexCoverageFile;
import dev.dancherbu.ccm.model.IndexCoverageProject;
import dev.dancherbu.ccm.model.IndexCoverageSnapshot;
import dev.dancherbu.ccm.model.ProjectReadinessReport;
import dev.dancherbu.ccm.model.ProjectReadinessStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ProjectReadinessGuardService {

    private final IndexInsightsService indexInsightsService;

    public ProjectReadinessGuardService(IndexInsightsService indexInsightsService) {
        this.indexInsightsService = indexInsightsService;
    }

    public void requireReady(String projectName, String toolName) {
        requireReadyProject(projectName, toolName);
        }

        public IndexCoverageProject requireReadyProject(String projectName, String toolName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException(
                    "projectName is required for " + toolName
                            + ". Use get_project_readiness first to select an eligible project.");
        }

        IndexCoverageSnapshot snapshot = indexInsightsService.snapshot();
        IndexCoverageProject project = snapshot.projects().stream()
                .filter(candidate -> candidate.projectName().equalsIgnoreCase(projectName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown projectName '" + projectName + "'. Check /api/metrics/index-overview for valid project names."));

        if (!project.embeddingCoverageThresholdMet()) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "%s is blocked for project '%s' because coverage is below readiness threshold (file %.2f%%, chunk %.2f%%, semantic %.2f%%; threshold %.2f%%).",
                    toolName,
                    project.projectName(),
                    project.fileCoveragePercent(),
                    project.chunkCoveragePercent(),
                    project.semanticCoveragePercent(),
                    project.embeddingCoverageThresholdPercent()));
        }
        return project;
    }

    public ProjectReadinessReport readiness(String requestedProjectName) {
        IndexCoverageSnapshot snapshot = indexInsightsService.snapshot();
        List<IndexCoverageProject> selected = filterProjects(snapshot.projects(), requestedProjectName);
        List<ProjectReadinessStatus> statuses = selected.stream()
                .sorted(Comparator.comparing(IndexCoverageProject::projectName))
                .map(this::toStatus)
                .toList();
        List<String> readyProjects = statuses.stream()
                .filter(ProjectReadinessStatus::ready)
                .map(ProjectReadinessStatus::projectName)
                .toList();
        List<String> blockedProjects = statuses.stream()
                .filter(status -> !status.ready())
                .map(ProjectReadinessStatus::projectName)
                .toList();
                boolean hasReadyProject = !readyProjects.isEmpty();
                String suggestedProjectName = hasReadyProject ? readyProjects.getFirst() : "";
                String suggestedResponseMode = hasReadyProject ? "lean" : "verbose";
                int suggestedTopK = hasReadyProject ? 6 : 3;
                int suggestedMaxFiles = hasReadyProject ? 4 : 2;
                List<String> readinessBlockers = readinessBlockers(statuses, snapshot.warnings());

        return new ProjectReadinessReport(
                Instant.now().toString(),
                requestedProjectName == null ? "" : requestedProjectName.trim(),
                snapshot.embeddingCoverageThresholdPercent(),
                snapshot.fileCoverageThresholdPercent(),
                snapshot.chunkCoverageThresholdPercent(),
                snapshot.semanticCoverageThresholdPercent(),
                                hasReadyProject,
                                suggestedProjectName,
                                suggestedResponseMode,
                                suggestedTopK,
                                suggestedMaxFiles,
                                readinessBlockers,
                readyProjects,
                blockedProjects,
                statuses,
                snapshot.warnings());
    }

        private List<String> readinessBlockers(List<ProjectReadinessStatus> statuses, List<String> snapshotWarnings) {
                if (statuses.isEmpty()) {
                        return List.of(
                                        "No indexed projects were discovered.",
                                        "Run or wait for indexing to complete, then call get_project_readiness again.");
                }
                List<String> blockers = new ArrayList<>();
                for (ProjectReadinessStatus status : statuses) {
                        if (status.ready()) {
                                continue;
                        }
                        blockers.add(String.format(
                                        Locale.ROOT,
                                        "%s is below threshold (file %.2f%%, chunk %.2f%%, semantic %.2f%%; threshold %.2f%%).",
                                        status.projectName(),
                                        status.fileCoveragePercent(),
                                        status.chunkCoveragePercent(),
                                        status.semanticCoveragePercent(),
                                        status.thresholdPercent()));
                }
                blockers.addAll(snapshotWarnings);
                return blockers.stream().distinct().limit(8).toList();
        }

    private List<IndexCoverageProject> filterProjects(List<IndexCoverageProject> projects, String requestedProjectName) {
        if (requestedProjectName == null || requestedProjectName.isBlank()) {
            return projects;
        }
        String requested = requestedProjectName.trim();
        return projects.stream()
                .filter(project -> project.projectName().equalsIgnoreCase(requested))
                .toList();
    }

    private ProjectReadinessStatus toStatus(IndexCoverageProject project) {
        List<String> sampleFiles = project.files().stream()
                .map(IndexCoverageFile::relativePath)
                .limit(5)
                .toList();
        return new ProjectReadinessStatus(
                project.projectName(),
                project.projectPath(),
                project.embeddingCoverageThresholdMet(),
                project.fileCoveragePercent(),
                project.chunkCoveragePercent(),
                project.semanticCoveragePercent(),
                project.embeddingCoverageThresholdPercent(),
                new ArrayList<>(project.warnings()),
                sampleFiles);
    }
}