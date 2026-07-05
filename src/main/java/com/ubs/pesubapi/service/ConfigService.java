package com.ubs.pesubapi.service;

import tools.jackson.databind.JsonNode;
import com.ubs.pesubapi.entity.ConfigEntry;
import com.ubs.pesubapi.repository.ConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Objects;
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

    public ConfigEntry put(String key, JsonNode value) {
        Objects.requireNonNull(key, "key");
        ConfigEntry entry = repo.findById(key).orElseGet(() -> {
            ConfigEntry e = new ConfigEntry();
            e.setKey(key);
            return e;
        });
        entry.setValue(value);
        ConfigEntry saved = repo.save(entry);
        cache.put(key, value);
        return saved;
    }
}
