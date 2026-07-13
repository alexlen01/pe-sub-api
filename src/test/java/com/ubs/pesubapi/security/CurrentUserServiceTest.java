package com.ubs.pesubapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserServiceTest {
    private final CurrentUserService service = new CurrentUserService();

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void returnsAuthenticatedEmployeeAttributesAndConvertedRole() {
        var identity = new UserIdentity("js25029", "John", "Smith", "john.smith@ubs.com");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            identity, null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST"))));

        var user = service.currentUser();
        assertThat(user.uuName()).isEqualTo("js25029");
        assertThat(user.firstName()).isEqualTo("John");
        assertThat(user.lastName()).isEqualTo("Smith");
        assertThat(user.email()).isEqualTo("john.smith@ubs.com");
        assertThat(user.role()).isEqualTo("Analyst");
        assertThat(service.displayName()).isEqualTo("John Smith");
        // Authorization/ownership must key on the stable uuName, never the display name.
        assertThat(service.uuName()).isEqualTo("js25029");
    }

    @Test void mapsManagerAuthorityToManagerRole() {
        var identity = new UserIdentity("mg10001", "Morgan", "Manager", "morgan.manager@ubs.com");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            identity, null, List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));

        // Regression guard: ROLE_MANAGER (not the retired ROLE_ATM) must resolve to the manager
        // label, otherwise /api/users/me reports a manager as "Viewer".
        assertThat(service.currentUser().role()).isEqualTo("Account/Transaction Manager");
    }
}
