package com.example.receipt.domain.employee.service;

import com.example.receipt.domain.employee.dto.*;
import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EmployeeService {
    private final EmployeeRepository employees;

    private final PasswordEncoder encoder;

    private final Clock clock;

    public record IssuedEmployee(EmployeeResponse employee, String setupToken, Instant expiresAt) {
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public IssuedEmployee issue(IssueEmployeeRequest request) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Employee employee = new Employee(request.loginId(), request.name(), request.role());
        Instant expires = Instant.now(clock).plus(Duration.ofHours(24));
        employee.invite(hash(token), expires);
        employees.saveAndFlush(employee);
        return new IssuedEmployee(EmployeeResponse.from(employee), token, expires);
    }

    @Transactional
    public void setPassword(SetPasswordRequest request) {
        Employee employee = employees.findForSetup(hash(request.token()))
                .orElseThrow(EmployeeService::invalidToken);
        if (!employee.active() || employee.passwordHash() != null ||
            !employee.setupExpiresAt().isAfter(Instant.now(clock))) throw invalidToken();
        validatePassword(request.password());
        employee.setPassword(encoder.encode(request.password()));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse active(Long id, boolean active, Long actorId) {
        if (id.equals(actorId) && !active) throw new ResponseStatusException(HttpStatus.CONFLICT, "자신을 비활성화할 수 없습니다.");
        Employee employee = employees.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        employee.setActive(active);
        return EmployeeResponse.from(employee);
    }

    /**
     * Only the offline bootstrap runner invokes this; the database lock serializes concurrent starts.
     */
    @Transactional
    public void bootstrap(String loginId, String password) {
        if (!Integer.valueOf(1).equals(employees.lockBootstrap()))
            throw new IllegalStateException("관리자 준비 잠금 행이 없습니다. Flyway 이관을 확인하세요.");
        if (employees.count() != 0) throw new IllegalStateException("최초 관리자 준비는 직원 테이블이 비어 있을 때만 가능합니다.");
        if (!loginId.matches("[a-z0-9._-]{3,64}")) throw new IllegalArgumentException("관리자 로그인 ID 형식이 잘못되었습니다.");
        validatePassword(password);
        Employee employee = new Employee(loginId, "초기 관리자", EmployeeRole.ADMIN);
        employee.setPassword(encoder.encode(password));
        employees.saveAndFlush(employee);
    }

    private static ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용할 수 없는 비밀번호 설정 토큰입니다.");
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("비밀번호는 12자 이상, UTF-8 72바이트 이하여야 합니다.");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
