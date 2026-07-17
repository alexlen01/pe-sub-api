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
 * <p>Role model mirrors RBAC_ROLES.md:
 * <ul>
 *   <li>ANALYST — day-to-day operator + configurator (writes operational data and configuration).</li>
 *   <li>MANAGER — Account/Transaction Manager, independent review authority (accept/reject Shadow BB).</li>
 *   <li>VIEWER — IT / read-only support (Intra ID App Role {@code APP_VIEWER}). May GET/download
 *       anything but is denied every mutating verb.</li>
 *   <li>SERVICE — service-to-service ingest feeds; never a human role.</li>
 * </ul>
 *
 * <p>Enforcement shape: specific write rules come first (config → ANALYST, ingest → SERVICE,
 * accept/reject → MANAGER). Reads (GET) under {@code /api} are open to any authenticated operator
 * including VIEWER. All remaining writes require an operator role, which structurally excludes
 * VIEWER (403). The maker ≠ checker separation on accept/reject is enforced in the controller.
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
                // Service-to-service ingest — never user-facing endpoints. The /ingest + /seed
                // feeds are pe-sub-jobs' startup loads; the cls-conc PATCH is its config feed
                // (operators edit the same map via PUT /api/config/eligibility instead).
                .requestMatchers(HttpMethod.POST, "/api/lpRecords/ingest").hasRole("SERVICE")
                .requestMatchers(HttpMethod.POST, "/api/lpRecords/seed").hasRole("SERVICE")
                .requestMatchers(HttpMethod.POST, "/api/facilities/ingest").hasRole("SERVICE")
                .requestMatchers(HttpMethod.POST, "/api/lp-master/ingest").hasRole("SERVICE")
                // Full LP Master wipe ahead of the one-off extract repopulate — machine-only,
                // run as the lp-master ingest job's pre-step. Kept SERVICE (not ANALYST like the
                // per-id DELETE) so a human curation token can never trigger a table-wide clear.
                .requestMatchers(HttpMethod.POST, "/api/lp-master/clear").hasRole("SERVICE")
                .requestMatchers(HttpMethod.PATCH, "/api/config/cls-conc-limit-defaults").hasRole("SERVICE")
                // Independent review (maker-checker) is Manager-only; maker ≠ checker is enforced
                // in SubmissionController. Analysts submit for review via POST /complete (an
                // operator write, allowed by the generic rules below).
                .requestMatchers(HttpMethod.POST, "/api/submissions/*/accept").hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/submissions/*/reject").hasRole("MANAGER")
                // Configuration surfaces are ANALYST-only to WRITE (RBAC matrix: MANAGER/VIEWER read
                // config but do not configure). Reads (GET) fall through to the operator-read rule.
                .requestMatchers(HttpMethod.PUT,    "/api/config/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.POST,   "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.PUT,    "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.PATCH,  "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.DELETE, "/api/field-mapping/**").hasRole("ANALYST")
                .requestMatchers(HttpMethod.DELETE, "/api/lp-master/**").hasRole("ANALYST")
                // Templates: any operator (incl. VIEWER) may read; only ANALYST may modify/import.
                .requestMatchers(HttpMethod.GET, "/api/bb-templates/**").authenticated()
                .requestMatchers("/api/bb-templates/**").hasRole("ANALYST")
                // Self-login audit event: any authenticated principal (incl. VIEWER) may record
                // that they started a session — it attributes activity, it does not mutate data.
                .requestMatchers(HttpMethod.POST, "/api/audit/login").authenticated()
                // Reads are open to any authenticated role (ANALYST, MANAGER, VIEWER). This is the
                // VIEWER surface: GET/download everything, including report exports.
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                // Every remaining write requires an operator role. VIEWER holds none of these, so
                // this is where the IT read-only role is denied all create/edit/delete/upload (403).
                .requestMatchers(HttpMethod.POST,   "/api/**").hasAnyRole("ANALYST", "MANAGER", "SERVICE")
                .requestMatchers(HttpMethod.PUT,    "/api/**").hasAnyRole("ANALYST", "MANAGER", "SERVICE")
                .requestMatchers(HttpMethod.PATCH,  "/api/**").hasAnyRole("ANALYST", "MANAGER", "SERVICE")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("ANALYST", "MANAGER", "SERVICE")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(new GatewayAuthenticationFilter(props),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
