package io.peek.core.intelligence;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("peek.llm")
public record LlmProperties(boolean enabled, boolean syntheticDemo, String endpoint, String model,
                            String apiKey, Duration timeout, Duration connectTimeout) {
    public boolean configured() {
        try {
            var uri = URI.create(endpoint == null ? "" : endpoint);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (local && "http".equals(uri.getScheme()) || "https".equals(uri.getScheme())
                    && "api.typesafe.ai".equals(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443)
                    && "/v1/systemone".equals(uri.getPath()))
                && model != null && !model.isBlank() && model.length() <= 200 && !hasControls(model)
                && apiKey != null && !hasControls(apiKey) && (local || !apiKey.isBlank())
                && timeout != null && timeout.toMillis() >= 100 && timeout.toMillis() <= 10_000
                && connectTimeout != null && connectTimeout.toMillis() >= 100 && connectTimeout.compareTo(timeout) <= 0;
        } catch (IllegalArgumentException error) { return false; }
    }
    private boolean hasControls(String value) { return value.chars().anyMatch(Character::isISOControl); }
    @Override public String toString() { return "LlmProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
