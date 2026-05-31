package com.ubs.pesubapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ubs.pesubapi.repository.ConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConfigService {

    private final ConfigRepository repo;
    private final ConcurrentHashMap<String, JsonNode> cache = new ConcurrentHashMap<>();

    public ConfigService(ConfigRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    public void load() {
        cache.clear();
        repo.findAll().forEach(e -> cache.put(e.getKey(), e.getValue()));
    }

    public Optional<JsonNode> get(String key) {
        return Optional.ofNullable(cache.get(key));
    }
}
