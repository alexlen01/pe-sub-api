package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.User;

import java.time.LocalDateTime;

/**
 * A directory entry — one person the gateway has authenticated. {@code displayName} is
 * pre-composed so screens rendering an attribution ("Submitted by …") never have to assemble a
 * name or decide what to show when the gateway sent no first/last name.
 */
public record UserDto(
    String        uuName,
    String        firstName,
    String        lastName,
    String        displayName,
    String        email,
    String        role,
    LocalDateTime lastSeenAt
) {
    public static UserDto from(User user) {
        return new UserDto(
            user.getUuName(), user.getFirstName(), user.getLastName(), user.displayName(),
            user.getEmail(), user.getRole(), user.getLastSeenAt());
    }
}
