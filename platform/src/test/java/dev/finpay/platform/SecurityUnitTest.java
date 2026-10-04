package dev.finpay.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import java.util.Map;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("unit")
@WebAppConfiguration
@SpringJUnitConfig({SecurityConfiguration.class, SecurityUnitTest.TestConfiguration.class})
class SecurityUnitTest {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void authenticate_missingToken_rejectsApiAccess() throws Exception {
        // Given
        var request = get("/api/test");

        // When / Then
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticate_invalidToken_rejectsApiAccess() throws Exception {
        // Given
        var request = get("/api/test").header("Authorization", "Bearer invalid");

        // When / Then
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticate_validCustomer_allowsApiAccess() throws Exception {
        // Given
        var request = get("/api/test").header("Authorization", "Bearer customer");

        // When / Then
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void authorize_customerRole_deniesMetrics() throws Exception {
        // Given
        var request = get("/actuator/prometheus").header("Authorization", "Bearer customer");

        // When / Then
        mvc.perform(request).andExpect(status().isForbidden());
    }

    @Test
    void authorize_adminRealmRole_allowsMetrics() throws Exception {
        // Given
        var request = get("/actuator/prometheus").header("Authorization", "Bearer admin");

        // When / Then
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void health_missingToken_allowsProbe() throws Exception {
        // Given
        var request = get("/actuator/health");

        // When / Then
        mvc.perform(request).andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean
        JwtDecoder decoder() {
            // This fixture tests filters/role mapping, not cryptographic JWT verification.
            return token -> {
                if (!List.of("customer", "admin").contains(token)) throw new BadJwtException("Invalid test token");
                return Jwt.withTokenValue(token).header("alg", "RS256").subject("alice")
                        .claim("realm_access", Map.of("roles", List.of("admin".equals(token) ? "ADMIN" : "CUSTOMER"))).build();
            };
        }

        @Bean TestEndpoints endpoints() { return new TestEndpoints(); }
    }

    @RestController
    static class TestEndpoints {
        @GetMapping({"/api/test", "/actuator/prometheus", "/actuator/health"})
        String ok() { return "ok"; }
    }
}
