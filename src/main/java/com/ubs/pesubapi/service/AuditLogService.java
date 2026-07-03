package com.ubs.pesubapi.service;

import com.ubs.pesubapi.entity.AuditLog;
import com.ubs.pesubapi.repository.AuditLogRepository;
import com.ubs.pesubapi.repository.FacilityRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AuditLogService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AuditLogRepository repo;
    private final FacilityRepository facilityRepo;

    public AuditLogService(AuditLogRepository repo, FacilityRepository facilityRepo) {
        this.repo         = repo;
        this.facilityRepo = facilityRepo;
    }

    public void log(String event, String detail, Integer facilityId, String ip) {
        log(event, detail, facilityId, null, ip);
    }

    public void log(String event, String detail, Integer facilityId, String userName, String ip) {
        AuditLog entry = new AuditLog();
        entry.setEvent(event);
        entry.setDetail(detail);
        entry.setFacilityId(facilityId);
        entry.setUserName(userName);
        entry.setIp(ip);
        repo.save(entry);
    }

    public String extractIp(HttpServletRequest req) {
        String remote = toIpv4(req.getRemoteAddr());
        String forwarded = req.getHeader("X-Forwarded-For");
        // X-Forwarded-For is client-supplied: honour it only when the direct peer is the
        // local reverse proxy (vite dev proxy / same-host gateway), otherwise any caller
        // could plant an arbitrary IP in the audit trail.
        if (forwarded != null && !forwarded.isBlank() && "127.0.0.1".equals(remote)) {
            return toIpv4(forwarded.split(",")[0].trim());
        }
        return remote;
    }

    private static String toIpv4(String ip) {
        if (ip == null) return "";
        // IPv6 loopback → IPv4 loopback
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        // IPv4-mapped IPv6 e.g. "::ffff:10.0.1.44"
        String lower = ip.toLowerCase();
        if (lower.startsWith("::ffff:")) return ip.substring(7);
        return ip;
    }

    public List<Map<String, Object>> query() {
        List<AuditLog> entries = repo.findAllByOrderByCreatedAtDesc();

        Set<Integer> ids = entries.stream()
            .filter(e -> e.getFacilityId() != null)
            .map(entry -> entry.getFacilityId())
            .collect(Collectors.toSet());

        Map<Integer, String> facilityNames = new HashMap<>();
        if (!ids.isEmpty()) {
            facilityRepo.findAllById(ids).forEach(f -> facilityNames.put(f.getId(), f.getName()));
        }

        return entries.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts",       e.getCreatedAt() != null ? e.getCreatedAt().format(TS_FMT) : "");
            row.put("event",    e.getEvent());
            row.put("detail",   e.getDetail()     != null ? e.getDetail() : "");
            row.put("facility", e.getFacilityId() != null
                ? facilityNames.getOrDefault(e.getFacilityId(), "—") : "—");
            row.put("user",     e.getUserName() != null ? e.getUserName() : "");
            row.put("ip",       e.getIp() != null ? e.getIp() : "");
            return row;
        }).collect(Collectors.toList());
    }
}
