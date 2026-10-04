package dev.finpay.intelligence;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class IntelligenceUnitTest {
    @ParameterizedTest
    @CsvSource({"swiggy dinner,FOOD", "ZOMATO,FOOD", "Amazon order,SHOPPING", "UBER,TRAVEL", "ELECTRICITY,BILLS", "Unknown merchant,OTHER", "'',OTHER"})
    void categorize_descriptions_returnsBaselineCategory(String description, String expected) {
        // Given
        String merchant = description;

        // When
        String category = CategoryRules.categorize(merchant);

        // Then
        assertThat(category).isEqualTo(expected);
    }

    @Test
    void categorize_nullDescription_returnsOther() {
        // Given
        String description = null;

        // When
        String category = CategoryRules.categorize(description);

        // Then
        assertThat(category).isEqualTo("OTHER");
    }

    @Test
    void summarize_disabledAi_preservesAuthoritativeReport() {
        // Given
        var narrator = new InsightNarrator(false, "http://localhost:11434", "qwen2.5:1.5b");
        var report = new SpendingProjection.Report("2026-09", "INR", List.of(), "RULE_BASED", "EVENTUALLY_CONSISTENT");

        // When
        var summary = narrator.summarize(report);

        // Then
        assertThat(summary.report()).isSameAs(report);
        assertThat(summary.mode()).isEqualTo("AI_DISABLED");
        assertThat(summary.narrative()).contains("2026-09");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://paid-provider.example/api", "http://remote.example",
            "http://localhost@remote.example", "http://localhost:11434?target=external",
            "http://user:secret@localhost:11434", "file:///tmp/ollama", "http://localhost:11434/v1"})
    void construct_nonLocalOrAmbiguousEndpoint_rejectsConfiguration(String endpoint) {
        // Given
        boolean enabled = true;

        // When / Then
        assertThatThrownBy(() -> new InsightNarrator(enabled, endpoint, "qwen2.5:1.5b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construct_cloudModel_rejectsConfiguration() {
        // Given
        String cloudModel = "cloud-model:cloud";

        // When / Then
        assertThatThrownBy(() -> new InsightNarrator(true, "http://localhost:11434", cloudModel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summarize_localModelWithoutApiKey_returnsNarrativeAndPreservesReport() throws Exception {
        // Given
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"message\":{\"content\":\"No outgoing payments were recorded.\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        var report = new SpendingProjection.Report("2026-09", "INR", List.of(), "RULE_BASED", "EVENTUALLY_CONSISTENT");
        try {
            var narrator = new InsightNarrator(true, "http://127.0.0.1:" + server.getAddress().getPort(), "qwen2.5:1.5b");

            // When
            var summary = narrator.summarize(report);

            // Then
            assertThat(summary.mode()).isEqualTo("LLM");
            assertThat(summary.report()).isSameAs(report);
            assertThat(summary.narrative()).isEqualTo("No outgoing payments were recorded.");
            assertThat(requestBody.get()).contains("qwen2.5:1.5b", "\"stream\":false", "2026-09");
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"200,AI_EMPTY_RESPONSE", "503,AI_UNAVAILABLE"})
    void summarize_missingOrUnavailableLocalModel_usesDeterministicFallback(int status, String expectedMode) throws Exception {
        // Given
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try {
            var narrator = new InsightNarrator(true, "http://127.0.0.1:" + server.getAddress().getPort(), "qwen2.5:1.5b");
            var report = new SpendingProjection.Report("2026-09", "INR", List.of(), "RULE_BASED", "EVENTUALLY_CONSISTENT");

            // When
            var summary = narrator.summarize(report);

            // Then
            assertThat(summary.mode()).isEqualTo(expectedMode);
            assertThat(summary.report()).isSameAs(report);
        } finally {
            server.stop(0);
        }
    }
}
