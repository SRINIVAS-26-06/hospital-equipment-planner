package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    @Query("select u from User u left join fetch u.department where lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    boolean existsByUsernameIgnoreCase(String username);

    long countByRoleAndActiveTrue(String role);

    boolean existsByUsernameAndRoleAndActiveTrue(String username, String role);

    @Query("select u from User u left join fetch u.department where u.id = :id")
    Optional<User> findWithDepartment(@Param("id") Long id);
}
