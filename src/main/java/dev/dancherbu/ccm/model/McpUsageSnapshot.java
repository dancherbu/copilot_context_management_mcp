package dev.dancherbu.ccm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record McpUsageSnapshot(
        @JsonProperty(required = true, value = "generatedAt") String generatedAt,
        @JsonProperty(required = true, value = "sseSessionsOpened") long sseSessionsOpened,
        @JsonProperty(required = true, value = "mcpMessagesReceived") long mcpMessagesReceived,
        @JsonProperty(required = true, value = "uniqueSessionIds") int uniqueSessionIds,
        @JsonProperty(required = true, value = "lastSseOpenedAt") String lastSseOpenedAt,
        @JsonProperty(required = true, value = "lastMcpMessageAt") String lastMcpMessageAt) {
}