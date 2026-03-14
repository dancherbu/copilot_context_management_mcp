package dev.dancherbu.ccm.chunking;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.index.ProjectIndexabilityService;
import dev.dancherbu.ccm.model.CodeChunk;
import dev.dancherbu.ccm.support.ChecksumUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

@Component
public class TreeSitterDocumentSplitter {

    private static final Set<String> SEMANTIC_NODE_TYPES = Set.of(
            "class_declaration",
            "interface_declaration",
            "enum_declaration",
            "annotation_type_declaration",
            "record_declaration",
            "constructor_declaration",
            "compact_constructor_declaration",
            "method_declaration");

    private final CcmProperties properties;
    private final ProjectIndexabilityService projectIndexabilityService;

    public TreeSitterDocumentSplitter(CcmProperties properties, ProjectIndexabilityService projectIndexabilityService) {
        this.properties = properties;
        this.projectIndexabilityService = projectIndexabilityService;
    }

    public List<CodeChunk> split(Path workspaceRoot, Path filePath, String source) {
        if (!filePath.toString().endsWith(".java")) {
            return List.of(fileChunk(workspaceRoot, filePath, source));
        }

        // Tree-sitter gives us structural nodes so modified Java files can be chunked by
        // classes and methods instead of arbitrary character windows.
        try (TSParser parser = new TSParser(); TSTree tree = initializeAndParse(parser, source)) {
            TSNode root = tree.getRootNode();
            List<CodeChunk> chunks = new ArrayList<>();
            collectChunks(workspaceRoot, filePath, source, root, chunks);
            return chunks.isEmpty() ? List.of(fileChunk(workspaceRoot, filePath, source)) : chunks;
        }
    }

    private TSTree initializeAndParse(TSParser parser, String source) {
        parser.setLanguage(new TreeSitterJava());
        return parser.parseString(null, source);
    }

    private void collectChunks(Path workspaceRoot, Path filePath, String source, TSNode node, List<CodeChunk> chunks) {
        if (node == null || node.isNull()) {
            return;
        }

        String nodeType = node.getType();
        if (SEMANTIC_NODE_TYPES.contains(nodeType)) {
            chunks.add(toChunk(workspaceRoot, filePath, source, node));
        }

        for (int index = 0; index < node.getNamedChildCount(); index++) {
            collectChunks(workspaceRoot, filePath, source, node.getNamedChild(index), chunks);
        }
    }

    private CodeChunk toChunk(Path workspaceRoot, Path filePath, String source, TSNode node) {
        byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
        int startByte = Math.max(0, Math.min(node.getStartByte(), utf8.length));
        int endByte = Math.max(startByte, Math.min(node.getEndByte(), utf8.length));
        String snippet = truncate(new String(utf8, startByte, endByte - startByte, StandardCharsets.UTF_8));
        String symbolName = extractSymbolName(source, node);

        return new CodeChunk(
                ChecksumUtils.sha256(filePath + ":" + node.getType() + ":" + startByte + ":" + endByte),
                workspaceRoot.toString(),
                filePath.toString(),
                "java",
                node.getType(),
                symbolName,
                node.getStartPoint().getRow() + 1,
                node.getEndPoint().getRow() + 1,
                snippet,
                ChecksumUtils.sha256(snippet),
                Instant.now());
    }

    private CodeChunk fileChunk(Path workspaceRoot, Path filePath, String source) {
        String snippet = truncate(source);
        long lineCount = Math.max(1, snippet.lines().count());
        return new CodeChunk(
                ChecksumUtils.sha256(filePath.toString()),
                workspaceRoot.toString(),
                filePath.toString(),
                projectIndexabilityService.detectLanguage(filePath),
                "file",
                filePath.getFileName().toString(),
                1,
                (int) Math.max(1, lineCount),
                snippet,
                ChecksumUtils.sha256(snippet),
                Instant.now());
    }

    private String truncate(String source) {
        int limit = properties.getMaxSnippetCharacters();
        if (limit <= 0 || source.length() <= limit) {
            return source;
        }
        return source.substring(0, limit);
    }

    private String extractSymbolName(String source, TSNode node) {
        TSNode nameNode = node.getChildByFieldName("name");
        if (nameNode != null && !nameNode.isNull()) {
            byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
            int startByte = Math.max(0, Math.min(nameNode.getStartByte(), utf8.length));
            int endByte = Math.max(startByte, Math.min(nameNode.getEndByte(), utf8.length));
            return new String(utf8, startByte, endByte - startByte, StandardCharsets.UTF_8).trim();
        }
        return node.getType();
    }
}
