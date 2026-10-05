package com.example.urlshortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * A single CorsConfigurationSource bean, used by both Spring MVC and Spring
 * Security's .cors(Customizer.withDefaults()) in SecurityConfig - one
 * source of truth instead of two separate CORS configs that could drift
 * out of sync with each other.
 */
@Configuration
public class CorsConfig {

    // Defaults to "*" so the app works out of the box for local dev and for
    // this being a portfolio/demo project. For any real deployment, set
    // app.cors.allowed-origins to the actual frontend origin(s) (e.g.
    // https://your-app.example.com) via an env var or profile-specific
    // config - a wildcard origin on a real API means any website can call
    // it from a visitor's browser, which is fine only because this app
    // uses Bearer-token auth (not cookies) and has nothing sensitive that
    // isn't already gated by that token or the management key.
    @Value("${app.cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // We authenticate with a Bearer token in the Authorization header,
        // not cookies, so credentialed CORS (which exists for cookies/TLS
        // client certs) isn't needed here.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
