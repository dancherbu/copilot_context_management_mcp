package dev.dancherbu.ccm.watch;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.index.ReindexCoordinator;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectDirectoryWatcher {

    private static final Logger logger = LoggerFactory.getLogger(ProjectDirectoryWatcher.class);

    private final CcmProperties properties;
    private final GitIgnoreMatcherService gitIgnoreMatcherService;
    private final ReindexCoordinator reindexCoordinator;

    private DirectoryWatcher watcher;
    private CompletableFuture<Void> watchFuture;

    public ProjectDirectoryWatcher(
            CcmProperties properties,
            GitIgnoreMatcherService gitIgnoreMatcherService,
            ReindexCoordinator reindexCoordinator) {
        this.properties = properties;
        this.gitIgnoreMatcherService = gitIgnoreMatcherService;
        this.reindexCoordinator = reindexCoordinator;
    }

    @PostConstruct
    void start() {
        if (properties.getWatchRoot() == null || !Files.exists(properties.getWatchRoot())) {
            logger.warn("Skipping watcher startup because watch root is unavailable: {}", properties.getWatchRoot());
            return;
        }

        try {
            this.watcher = DirectoryWatcher.builder()
                    .path(properties.getWatchRoot())
                    .fileHashing(false)
                    .listener(this::onEvent)
                    .build();
            this.watchFuture = watcher.watchAsync();
            logger.info("Started directory watcher for {}", properties.getWatchRoot());
        } catch (IOException ex) {
            logger.warn("Unable to start directory watcher", ex);
        }
    }

    @PreDestroy
    void stop() {
        if (watchFuture != null) {
            watchFuture.cancel(true);
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ex) {
                logger.debug("Error while closing watcher", ex);
            }
        }
    }

    private void onEvent(DirectoryChangeEvent event) {
        if (event.isDirectory() || gitIgnoreMatcherService.shouldIgnore(event.path())) {
            return;
        }

        switch (event.eventType()) {
            case CREATE -> {
                logger.debug("Watcher create event for {}", event.path());
                reindexCoordinator.handleCreate(event.path());
            }
            case MODIFY -> {
                logger.debug("Watcher modify event for {}", event.path());
                reindexCoordinator.handleModify(event.path());
            }
            case DELETE -> {
                logger.debug("Watcher delete event for {}", event.path());
                reindexCoordinator.handleDelete(event.path());
            }
            default -> logger.debug("Ignoring watcher event {} for {}", event.eventType(), event.path());
        }
    }
}
