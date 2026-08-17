package com.ubs.pesubapi.repository;

import com.ubs.pesubapi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUuName(String uuName);

    List<User> findAllByOrderByLastNameAscFirstNameAscUuNameAsc();
}
