package com.ubs.pesubapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Establishes the request's {@link Authentication} from either a fixed dev identity or the
 * SSO-proxy identity headers, depending on {@link SecurityProperties#getMode()}.
 *
 * <p>If the security context is already populated — e.g. by {@code @WithMockUser} in a test — this
 * filter defers to it and does nothing, so authorization tests can assert arbitrary roles.
 *
 * <p>DEV mode honors the dev role-switcher's {@code X-Auth-*} headers when they are present, so a
 * local operator can exercise different identities/roles without running full gateway mode (which
 * would 401 every header-less caller). When no headers are present it falls back to the fixed dev
 * identity, so header-less callers (service jobs, direct curl, the reachability ping) still work.
 * This convenience is DEV-only; production runs GATEWAY, which trusts only the proxy headers.
 */
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private final SecurityProperties props;

    public GatewayAuthenticationFilter(SecurityProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = switch (props.getMode()) {
            case DEV     -> devAuthentication(request);
            case GATEWAY -> fromHeaders(request);
        };
        if (auth != null) {
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        // When auth is null (GATEWAY mode, missing header) the context stays empty and the
        // authorization layer answers 401 for any protected endpoint.
        filterChain.doFilter(request, response);
    }

    // DEV mode: prefer the dev switcher's headers when present, else the fixed dev identity.
    private Authentication devAuthentication(HttpServletRequest request) {
        Authentication fromHeaders = fromHeaders(request);
        if (fromHeaders != null) return fromHeaders;
        return authentication(new UserIdentity(props.getDevUser(), props.getDevFirstName(),
            props.getDevLastName(), props.getDevEmail()), props.getDevRoles());
    }

    private Authentication fromHeaders(HttpServletRequest request) {
        String user = request.getHeader(props.getUserHeader());
        if (user == null || user.isBlank()) return null;
        String rolesRaw = request.getHeader(props.getRolesHeader());
        List<String> roles = (rolesRaw == null || rolesRaw.isBlank())
            ? List.of()
            : Arrays.stream(rolesRaw.split(",")).map(s -> s.trim()).filter(s -> !s.isEmpty()).toList();
        String uuName = user.trim();
        String email = header(request, props.getEmailHeader());
        return authentication(new UserIdentity(uuName,
            header(request, props.getFirstNameHeader()), header(request, props.getLastNameHeader()),
            email.isBlank() ? uuName : email), roles);
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value.trim();
    }

    private Authentication authentication(UserIdentity user, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = roles.stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
            .toList();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
