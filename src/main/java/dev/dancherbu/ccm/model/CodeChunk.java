package dev.dancherbu.ccm.model;

import java.time.Instant;

public record CodeChunk(
        String id,
        String workspacePath,
        String filePath,
        String language,
        String nodeType,
        String symbolName,
        int startLine,
        int endLine,
        String snippet,
        String checksum,
        Instant indexedAt) {
}
