package dev.dancherbu.ccm.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

class LocalInferenceGuardTest {

    @Test
    void allowsKnownLocalEndpointWhenLocalOnlyIsEnabled() {
        CcmProperties properties = new CcmProperties();
        properties.getOllama().setLocalOnly(true);

        LocalInferenceGuard guard = new LocalInferenceGuard(properties);
        ReflectionTestUtils.setField(guard, "ollamaBaseUrl", "http://host.docker.internal:11434");

        assertThatCode(() -> guard.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonLocalEndpointWhenLocalOnlyIsEnabled() {
        CcmProperties properties = new CcmProperties();
        properties.getOllama().setLocalOnly(true);

        LocalInferenceGuard guard = new LocalInferenceGuard(properties);
        ReflectionTestUtils.setField(guard, "ollamaBaseUrl", "https://external-llm.example/v1");

        assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start with a non-local Ollama endpoint");
    }

    @Test
    void skipsValidationWhenLocalOnlyIsDisabled() {
        CcmProperties properties = new CcmProperties();
        properties.getOllama().setLocalOnly(false);

        LocalInferenceGuard guard = new LocalInferenceGuard(properties);
        ReflectionTestUtils.setField(guard, "ollamaBaseUrl", "https://example.com");

        assertThatCode(() -> guard.run(new DefaultApplicationArguments(new String[0]))).doesNotThrowAnyException();
    }
}
