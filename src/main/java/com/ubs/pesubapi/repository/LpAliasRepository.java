package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.LpAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LpAliasRepository extends JpaRepository<LpAlias, Integer> {

    Optional<LpAlias> findByUploadedName(String uploadedName);

    List<LpAlias> findByUploadedNameIn(Collection<String> uploadedNames);

    List<LpAlias> findByLpMasterIdOrderByUploadedNameAsc(Integer lpMasterId);
}
