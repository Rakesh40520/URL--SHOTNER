package com.example.urlshortener.security;

import com.example.urlshortener.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // JwtAuthenticationFilter is deliberately NOT a @Component. If it
        // were, Spring Boot would auto-register every Filter bean it finds
        // as a *second*, independent servlet filter in addition to the one
        // Spring Security registers below - so it would run twice per
        // request. Building it here and adding it only to Spring
        // Security's own chain (via addFilterBefore) avoids that trap.
        JwtAuthenticationFilter jwtAuthFilter = new JwtAuthenticationFilter(jwtService, userRepository);

        http
                // Stateless JWT API, no server-side session/cookie to forge -
                // CSRF protection (which exists to protect cookie-based
                // sessions) isn't relevant here.
                .csrf(AbstractHttpConfigurer::disable)
                // Picks up the CorsConfigurationSource bean from CorsConfig.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without this, Spring Security gives every unauthenticated
                // request a placeholder "anonymousUser" principal instead of
                // no principal at all, which would make
                // @AuthenticationPrincipal User user resolve inconsistently
                // for anonymous requests. Disabling it keeps that null,
                // which is what UrlController and UrlShortenerService
                // check for.
                .anonymous(AbstractHttpConfigurer::disable)
                // The H2 console renders itself inside a frame; Spring
                // Security blocks framing by default, which would otherwise
                // break the /h2-console page as soon as security is added.
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS preflight
                        .requestMatchers(HttpMethod.GET, "/api/v1/urls/my", "/api/v1/urls/my/summary", "/api/v1/urls/my/export").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/subscription").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/subscription").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/urls/**").authenticated()
                        // Everything else - shortening, redirects, stats,
                        // click history, auth endpoints, the frontend, the
                        // H2 console, Swagger - stays open, matching how
                        // the app already behaved before auth existed.
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
