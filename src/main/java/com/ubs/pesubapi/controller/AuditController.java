package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.security.CurrentUserService;
import com.ubs.pesubapi.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLogService auditService;
    private final CurrentUserService currentUser;

    public AuditController(AuditLogService auditService, CurrentUserService currentUser) {
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return auditService.query();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(HttpServletRequest request) {
        auditService.log("Login", "User login", null, currentUser.displayName(), auditService.extractIp(request));
        return ResponseEntity.ok().build();
    }
}
