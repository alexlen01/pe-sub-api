package com.ubs.pesubapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionLoggingFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String LEGACY_HEADER = "X-Transaction-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Logger log = LoggerFactory.getLogger(TransactionLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String txId = transactionId(request);
        long started = System.nanoTime();
        MDC.put(MDC_KEY, txId);
        putHeader(request, "X-Session-Id", "session_id");
        putHeader(request, "X-Tenant-Id", "tenant_id");
        putHeader(request, "X-Fund-Id", "fund_id");
        response.setHeader(HEADER, txId);
        response.setHeader(LEGACY_HEADER, txId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                MDC.put("user_id", auth.getName());
                MDC.put("user_role", auth.getAuthorities().stream()
                    .map(ga -> ga.getAuthority().replaceFirst("^ROLE_", ""))
                    .sorted().reduce((a, b) -> a + "," + b).orElse(""));
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            if (isApiRequest(request)) {
                log.info("HTTP {} {} status={} durationMs={} remote={} userAgent='{}'",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                    request.getRemoteAddr(), headerOrBlank(request, "User-Agent"));
            }
            MDC.clear();
        }
    }

    private String transactionId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming == null || incoming.isBlank()) incoming = request.getHeader(LEGACY_HEADER);
        if (incoming != null && !incoming.isBlank()) {
            return incoming.trim().substring(0, Math.min(incoming.trim().length(), 64));
        }
        return UUID.randomUUID().toString();
    }

    private void putHeader(HttpServletRequest request, String header, String key) {
        String value = request.getHeader(header);
        if (value != null && !value.isBlank()) MDC.put(key, value.trim().substring(0, Math.min(64, value.trim().length())));
    }

    private boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/api/") || uri.equals("/api"));
    }

    private String headerOrBlank(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value != null ? value : "";
    }
}
