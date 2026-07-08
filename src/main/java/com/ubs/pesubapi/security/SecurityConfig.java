package com.ubs.pesubapi.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * API security. Stateless, token/header-based (no sessions, no CSRF cookies). Identity is
 * established by {@link GatewayAuthenticationFilter}; this chain defines what each role may reach.
 *
 * <p>Role model mirrors RBAC_ROLES.md: ANALYST (day-to-day operator + configurator) and ATM
 * (Account/Transaction Manager — review authority). Configuration surfaces are ANALYST-gated per
 * the permission matrix. The 4-eye separation on submission completion is a Phase-2 workflow
 * control and is intentionally not enforced here yet.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final AuthenticationEntryPoint UNAUTHORIZED =
        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties props) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .anonymous(anonymous -> anonymous.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(UNAUTHORIZED))
            .authorizeHttpRequests(auth -> auth
                // CORS preflight carries no identity header; never gate it behind auth.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Public: liveness/health and the SSE stream (EventSource cannot send headers).
                .requestMatchers("/api/ping", "/health", "/api/notifications/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Service-to-service ingest — never a user-facing endpoint.
                .requestMatchers(HttpMethod.POST, "/api/lpRecords/ingest").hasRole("SERVICE")
                // Configuration surfaces are ANALYST-only (RBAC matrix: ATM does not configure).
                .requestMatchers(HttpMethod.PUT,    "/api/config/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.POST,   "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.PUT,    "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.PATCH,  "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.DELETE, "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers("/api/bb-templates/**").hasRole("ANALYST")
                // Everything else under /api requires an authenticated operator (ANALYST or ATM).
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(new GatewayAuthenticationFilter(props),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
