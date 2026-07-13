package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.dto.CurrentUserDto;
import com.ubs.pesubapi.security.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final CurrentUserService users;

    public UserController(CurrentUserService users) { this.users = users; }

    @GetMapping("/me")
    public CurrentUserDto me() { return users.currentUser(); }
}
