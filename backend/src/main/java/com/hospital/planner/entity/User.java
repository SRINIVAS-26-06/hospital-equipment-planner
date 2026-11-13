package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false) public String username;
    @Column(name = "password_hash", nullable = false) public String passwordHash;
    @Column(name = "full_name", nullable = false) public String fullName = "";
    public String email;
    @Column(nullable = false) public String role;
    @ManyToOne @JoinColumn(name = "department_id") public Department department;
    @Column(nullable = false) public boolean active = true;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) public Instant createdAt;
}
