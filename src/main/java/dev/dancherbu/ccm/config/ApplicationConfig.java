package dev.dancherbu.ccm.config;

import dev.dancherbu.ccm.vector.CodebaseVectorStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

@Configuration
public class ApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfig.class);

    @Bean
    SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel, CcmProperties properties) {
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        Path backingPath = Path.of(properties.getVectorStoreFile());
        File backingFile = backingPath.toFile();
        if (backingFile.exists()) {
            try {
                vectorStore.load(backingFile);
            } catch (RuntimeException ex) {
                quarantineCorruptVectorStore(backingPath, ex);
            }
        } else {
            try {
                Path parent = backingPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (Exception ignored) {
                // The application can still run with an in-memory-only vector store.
            }
        }
        return vectorStore;
    }

    @Bean
    CodebaseVectorStore codebaseVectorStore(SimpleVectorStore simpleVectorStore, CcmProperties properties) {
        return new CodebaseVectorStore(simpleVectorStore, Path.of(properties.getVectorStoreFile()));
    }

    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a precise local code impact analyst. Return strict JSON when asked.")
                .build();
    }

    private void quarantineCorruptVectorStore(Path backingPath, RuntimeException ex) {
        Path quarantinePath = backingPath.resolveSibling(
                backingPath.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(backingPath, quarantinePath, StandardCopyOption.REPLACE_EXISTING);
            logger.warn(
                    "Vector store at {} was unreadable and has been moved to {}. Starting with an empty in-memory store.",
                    backingPath,
                    quarantinePath,
                    ex);
        } catch (Exception moveEx) {
            logger.warn(
                    "Vector store at {} was unreadable and could not be moved aside. Starting with an empty in-memory store.",
                    backingPath,
                    moveEx);
            logger.debug("Original vector-store load failure", ex);
        }
    }

}
