package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.FmAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FmAliasRepository extends JpaRepository<FmAlias, Integer> {
    List<FmAlias> findAllByOrderByCanonicalFieldIdAscAliasSortAsc();
    List<FmAlias> findByCanonicalFieldIdOrderByAliasSortAsc(Integer canonicalFieldId);
    Optional<FmAlias> findByAliasTextIgnoreCase(String aliasText);
}
