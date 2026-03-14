package dev.dancherbu.ccm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.model.McpUsageSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McpUsageTelemetryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndResetsUsageTelemetry() {
        Path usageFile = tempDir.resolve("usage-metrics.json");

        McpUsageTelemetryService service = new McpUsageTelemetryService(new ObjectMapper(), properties(usageFile));
        service.recordSseOpen();
        service.recordMcpMessage("session-a");
        McpUsageSnapshot first = service.snapshot();

        assertThat(first.sseSessionsOpened()).isEqualTo(1);
        assertThat(first.mcpMessagesReceived()).isEqualTo(1);
        assertThat(first.uniqueSessionIds()).isEqualTo(1);

        McpUsageTelemetryService reloaded = new McpUsageTelemetryService(new ObjectMapper(), properties(usageFile));
        McpUsageSnapshot second = reloaded.snapshot();

        assertThat(second.sseSessionsOpened()).isEqualTo(1);
        assertThat(second.mcpMessagesReceived()).isEqualTo(1);
        assertThat(second.uniqueSessionIds()).isEqualTo(1);

        McpUsageSnapshot reset = reloaded.reset();
        assertThat(reset.sseSessionsOpened()).isZero();
        assertThat(reset.mcpMessagesReceived()).isZero();
        assertThat(reset.uniqueSessionIds()).isZero();

        McpUsageTelemetryService afterReset = new McpUsageTelemetryService(new ObjectMapper(), properties(usageFile));
        McpUsageSnapshot third = afterReset.snapshot();

        assertThat(third.sseSessionsOpened()).isZero();
        assertThat(third.mcpMessagesReceived()).isZero();
        assertThat(third.uniqueSessionIds()).isZero();
    }

    private static CcmProperties properties(Path usageFile) {
        CcmProperties properties = new CcmProperties();
        properties.getMetrics().setUsageFile(usageFile.toString());
        return properties;
    }
}
