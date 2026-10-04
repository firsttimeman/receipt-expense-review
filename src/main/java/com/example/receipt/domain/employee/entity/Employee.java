package com.example.receipt.domain.employee.entity;

import com.example.receipt.domain.employee.model.EmployeeRole;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "employees")
public class Employee {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Column(name = "login_id", nullable = false, unique = true, length = 64)
    private String loginId;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EmployeeRole role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "setup_token_hash", unique = true, length = 64)
    private String setupTokenHash;

    @Column(name = "setup_expires_at")
    private Instant setupExpiresAt;

    protected Employee() {
    }

    public Employee(String loginId, String name, EmployeeRole role) {
        this.loginId = loginId;
        this.name = name;
        this.role = role;
    }

    public Long id() {
        return id;
    }

    public String loginId() {
        return loginId;
    }

    public long sessionVersion() {
        return sessionVersion;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String name() {
        return name;
    }

    public EmployeeRole role() {
        return role;
    }

    public boolean active() {
        return active;
    }

    public Instant setupExpiresAt() {
        return setupExpiresAt;
    }

    public void invite(String hash, Instant expires) {
        setupTokenHash = hash;
        setupExpiresAt = expires;
    }

    public void setPassword(String hash) {
        passwordHash = hash;
        setupTokenHash = null;
        setupExpiresAt = null;
    }

    public void setActive(boolean active) {
        if (this.active && !active) {
            // 이전 로그인 세션을 모두 무효화하며, 재활성화해도 버전은 되돌리지 않습니다.
            sessionVersion++;
        }
        this.active = active;
    }
}
