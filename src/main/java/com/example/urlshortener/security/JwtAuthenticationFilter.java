package com.example.urlshortener.security;

import com.example.urlshortener.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Reads an "Authorization: Bearer &lt;token&gt;" header if present and, if
 * the token is valid, sets the corresponding User as the authenticated
 * principal for this request.
 *
 * <p>Deliberately does NOT reject the request here if the header is
 * missing or the token is invalid - that's what lets endpoints like
 * POST /api/v1/urls stay usable both anonymously and by logged-in users
 * (the service sets the URL's owner only when a principal is present).
 * Endpoints that actually require auth reject unauthenticated requests via
 * SecurityConfig's .authenticated() rule instead, at which point Spring
 * Security's own exception handling (see RestAuthenticationEntryPoint)
 * takes over.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // Single parse (and single signature verification) per request,
            // rather than isValid() + extractEmail() each re-parsing the
            // token from scratch.
            jwtService.extractEmailIfValid(token).ifPresent(email ->
                    userRepository.findByEmail(email).ifPresent(user ->
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()))));
        }

        filterChain.doFilter(request, response);
    }
}
