package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.CurrentUserDto;
import com.ubs.pesubapi.dto.UserDto;
import com.ubs.pesubapi.exception.ResourceNotFoundException;
import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.UserDirectoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final CurrentUserService   users;
    private final UserDirectoryService directory;

    public UserController(CurrentUserService users, UserDirectoryService directory) {
        this.users     = users;
        this.directory = directory;
    }

    /** The caller's own identity, straight from the request principal — never a directory read. */
    @GetMapping("/me")
    public CurrentUserDto me() { return users.currentUser(); }

    /** Everyone who has authenticated, for rendering names alongside stored uuNames. */
    @GetMapping
    public List<UserDto> all() {
        return directory.findAll().stream().map(UserDto::from).toList();
    }

    /** One directory entry by its stable identity (e.g. {@code le05751}). */
    @GetMapping("/{uuName}")
    public UserDto byUuName(@PathVariable String uuName) {
        return directory.findByUuName(uuName).map(UserDto::from)
            .orElseThrow(() -> new ResourceNotFoundException("User " + uuName + " not found"));
    }
}
