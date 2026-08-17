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

    /** Header carrying the comma-separated role list, e.g. "ANALYST,MANAGER" (GATEWAY mode). */
    private String rolesHeader = "X-Auth-Roles";
    private String firstNameHeader = "X-Auth-First-Name";
    private String lastNameHeader = "X-Auth-Last-Name";
    private String emailHeader = "X-Auth-Email";

    /**
     * Identity used for every request in DEV mode. A uuName, not an email — it is the natural key
     * of the users directory, so a dev run produces a directory row shaped like a real one.
     */
    private String devUser = "js25029";
    private String devFirstName = "John";
    private String devLastName = "Smith";
    private String devEmail = "john.smith@ubs.com";

    /** Roles granted to {@link #devUser} in DEV mode. */
    private List<String> devRoles = List.of("ANALYST");

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String getUserHeader() { return userHeader; }
    public void setUserHeader(String userHeader) { this.userHeader = userHeader; }

    public String getRolesHeader() { return rolesHeader; }
    public void setRolesHeader(String rolesHeader) { this.rolesHeader = rolesHeader; }
    public String getFirstNameHeader() { return firstNameHeader; }
    public void setFirstNameHeader(String value) { this.firstNameHeader = value; }
    public String getLastNameHeader() { return lastNameHeader; }
    public void setLastNameHeader(String value) { this.lastNameHeader = value; }
    public String getEmailHeader() { return emailHeader; }
    public void setEmailHeader(String value) { this.emailHeader = value; }

    public String getDevUser() { return devUser; }
    public void setDevUser(String devUser) { this.devUser = devUser; }
    public String getDevFirstName() { return devFirstName; }
    public void setDevFirstName(String value) { this.devFirstName = value; }
    public String getDevLastName() { return devLastName; }
    public void setDevLastName(String value) { this.devLastName = value; }
    public String getDevEmail() { return devEmail; }
    public void setDevEmail(String value) { this.devEmail = value; }

    public List<String> getDevRoles() { return devRoles; }
    public void setDevRoles(List<String> devRoles) { this.devRoles = devRoles; }
}
