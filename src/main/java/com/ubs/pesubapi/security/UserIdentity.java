package com.ubs.pesubapi.security;

import java.security.Principal;

/** Authenticated employee attributes supplied by local configuration or the trusted SSO gateway. */
public record UserIdentity(String uuName, String firstName, String lastName, String email) implements Principal {
    @Override public String getName() { return uuName; }
}
