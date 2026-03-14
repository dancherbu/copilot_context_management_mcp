package dev.dancherbu.ccm;

import dev.dancherbu.ccm.config.CcmProperties;
import dev.dancherbu.ccm.index.ReindexCoordinator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(CcmProperties.class)
public class CopilotContextManagementMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CopilotContextManagementMcpApplication.class, args);
    }

    @Bean
    CommandLineRunner initialIndex(ReindexCoordinator reindexCoordinator) {
        return args -> reindexCoordinator.fullReindex();
    }
}
