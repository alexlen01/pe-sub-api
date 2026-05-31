package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.Facility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FacilityRepository extends JpaRepository<Facility, Integer> {
    Optional<Facility> findByName(String name);
}
