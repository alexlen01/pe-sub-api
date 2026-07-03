package com.ubs.pesubapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Authentication configuration for the two supported deployment shapes.
 *
 * <p>{@code mode=DEV} (default): every request is authenticated as {@link #devUser} holding
 * {@link #devRoles}. This keeps local standalone runs and the header-less pe-sub-ui working, and
 * lets the integration suite run without a login flow.
 *
 * <p>{@code mode=GATEWAY} (production behind UBS SSO): identity is taken from headers injected by
 * the trusted reverse proxy ({@link #userHeader} / {@link #rolesHeader}). A request without a
 * valid user header is rejected 401 — the application never trusts an unauthenticated caller.
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    public enum Mode { DEV, GATEWAY }

    /** DEV = inject a fixed dev identity; GATEWAY = require SSO-proxy identity headers. */
    private Mode mode = Mode.DEV;

    /** Header carrying the authenticated user id/email (GATEWAY mode). */
    private String userHeader = "X-Auth-User";

    /** Header carrying the comma-separated role list, e.g. "ANALYST,ATM" (GATEWAY mode). */
    private String rolesHeader = "X-Auth-Roles";

    /** Identity used for every request in DEV mode. */
    private String devUser = "local.analyst@ubs.dev";

    /** Roles granted to {@link #devUser} in DEV mode. */
    private List<String> devRoles = List.of("ANALYST");

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String getUserHeader() { return userHeader; }
    public void setUserHeader(String userHeader) { this.userHeader = userHeader; }

    public String getRolesHeader() { return rolesHeader; }
    public void setRolesHeader(String rolesHeader) { this.rolesHeader = rolesHeader; }

    public String getDevUser() { return devUser; }
    public void setDevUser(String devUser) { this.devUser = devUser; }

    public List<String> getDevRoles() { return devRoles; }
    public void setDevRoles(List<String> devRoles) { this.devRoles = devRoles; }
}
