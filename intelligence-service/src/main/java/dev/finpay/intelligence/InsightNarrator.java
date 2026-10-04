package dev.finpay.intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
public class InsightNarrator {
    private static final Logger LOG = LoggerFactory.getLogger(InsightNarrator.class);
    private static final String CONTENT_FIELD = "content";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "ollama", "host.docker.internal");
    private static final Set<String> LOCAL_MODELS = Set.of("qwen2.5:1.5b", "qwen2.5:7b");
    // One inference at a time protects CPU-only development machines from overload.
    private final Semaphore capacity = new Semaphore(1);
    private final RestClient client;
    private final String model;
    private final boolean enabled;

    public InsightNarrator(@Value("${AI_ENABLED:false}") boolean enabled,
            @Value("${OLLAMA_BASE_URL:http://localhost:11434}") String baseUrl,
            @Value("${AI_MODEL:qwen2.5:1.5b}") String model) {
        URI endpoint = URI.create(baseUrl);
        if (endpoint.getHost() == null || !LOCAL_HOSTS.contains(endpoint.getHost())
                || !Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("AI must use the local Ollama endpoint; public providers are disabled");
        }
        if (!LOCAL_MODELS.contains(model)) throw new IllegalArgumentException("Choose an approved local Qwen model");
        this.enabled = enabled;
        this.model = model;
        // Keep local inference below the gateway's 15-second timeout; cold models return a safe fallback.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Summary summarize(SpendingProjection.Report report) {
        if (!enabled) return fallback(report, "AI_DISABLED");
        if (!capacity.tryAcquire()) return fallback(report, "AI_BUSY");
        try {
            var request = Map.of("model", model, "stream", false, "options", Map.of("num_predict", 250, "temperature", 0), "messages", List.of(
                    Map.of("role", "system", CONTENT_FIELD, "Summarize the supplied financial aggregates in two sentences. Amounts are INR paise. Do not invent transactions, recommend investments, or claim to execute actions. The structured report is authoritative."),
                    Map.of("role", "user", CONTENT_FIELD, report.toString())));
            JsonNode response = client.post().uri("/api/chat").body(request).retrieve().body(JsonNode.class);
            String text = response == null ? "" : response.path("message").path(CONTENT_FIELD).asString("");
            if (text.isBlank()) return fallback(report, "AI_EMPTY_RESPONSE");
            return new Summary(report, text, "LLM", "AI text may be inaccurate; use the structured totals as the source of truth.");
        } catch (org.springframework.web.client.RestClientException exception) {
            // Do not log financial data or prompts, even when inference is local.
            LOG.warn("AI summary unavailable: {}", exception.getClass().getSimpleName());
            return fallback(report, "AI_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    private static Summary fallback(SpendingProjection.Report report, String mode) {
        return new Summary(report, "The structured report contains your recorded outgoing payments for " + report.month() + ".",
                mode, "No AI-generated analysis was used.");
    }

    public record Summary(SpendingProjection.Report report, String narrative, String mode, String disclaimer) { }
}
