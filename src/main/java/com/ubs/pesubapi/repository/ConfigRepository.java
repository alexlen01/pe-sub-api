package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.ConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigRepository extends JpaRepository<ConfigEntry, String> {}
