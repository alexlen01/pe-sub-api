package com.ubs.pesubapi.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.ubs.pesubapi.entity.ConfigEntry;
import com.ubs.pesubapi.repository.ConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
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

    /**
     * Merges numeric entries into a jsonb map config value: fed keys overwrite matching
     * entries and every key the feed does not mention is preserved — the same semantics as
     * the {@code config.value || EXCLUDED.value} upsert the cls-conc-limits batch job used
     * when it wrote the config table directly. Persists and refreshes the cache in one step,
     * so callers never need a separate {@code /reload}.
     */
    public JsonNode mergeIntoMap(String key, Map<String, BigDecimal> entries) {
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        // Cast before deepCopy: ObjectNode.deepCopy() is covariantly typed, so no generic
        // inference (and no unchecked-cast surface) is involved.
        get(key).filter(node -> node != null && node.isObject())
            .ifPresent(existing -> merged.setAll(((ObjectNode) existing).deepCopy()));
        entries.forEach(merged::put);
        put(key, merged);
        return merged;
    }
}
