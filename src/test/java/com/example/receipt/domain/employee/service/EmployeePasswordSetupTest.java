package com.example.receipt.domain.employee.service;

import com.example.receipt.domain.employee.dto.SetPasswordRequest;
import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmployeePasswordSetupTest {
    private static final long EMPLOYEE_ID = 42L;
    private static final String OLD_TOKEN = "synthetic-old-setup-token";
    private static final String PASSWORD = "synthetic-password-123";
    private static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmployeeService service = new EmployeeService(
            employees, encoder, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void rejectsTokenReplacedAfterEmployeeIdLookupBeforeTheLockIsAcquired() throws Exception {
        Employee employee = invitedEmployee();
        String newTokenHash = hash("synthetic-reissued-setup-token");
        Instant newExpiry = NOW.plus(Duration.ofHours(24));
        when(employees.findIdBySetupTokenHash(hash(OLD_TOKEN))).thenReturn(Optional.of(EMPLOYEE_ID));
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenAnswer(invocation -> {
            // 직원 번호를 찾은 직후 다른 요청이 토큰 재발급을 완료한 상황입니다.
            employee.invite(newTokenHash, newExpiry);
            return Optional.of(employee);
        });

        assertInvalidOldToken();

        assertThat(employee.passwordHash()).isNull();
        assertThat(employee.hasSetupToken(newTokenHash)).isTrue();
        assertThat(employee.hasSetupToken(hash(OLD_TOKEN))).isFalse();
        assertThat(employee.setupExpiresAt()).isEqualTo(newExpiry);
        verifyNoInteractions(encoder);
    }

    @Test
    void preservesPasswordSetByAnotherRequestWhileWaitingForTheLock() throws Exception {
        Employee employee = invitedEmployee();
        String establishedPasswordHash = "synthetic-existing-password-hash";
        when(employees.findIdBySetupTokenHash(hash(OLD_TOKEN))).thenReturn(Optional.of(EMPLOYEE_ID));
        when(employees.findByIdForUpdate(EMPLOYEE_ID)).thenAnswer(invocation -> {
            // 번호 조회 시 유효했던 토큰을 다른 요청이 먼저 사용했습니다.
            employee.setPassword(establishedPasswordHash);
            return Optional.of(employee);
        });

        assertInvalidOldToken();

        assertThat(employee.passwordHash()).isEqualTo(establishedPasswordHash);
        assertThat(employee.hasSetupToken(hash(OLD_TOKEN))).isFalse();
        assertThat(employee.setupExpiresAt()).isNull();
        verifyNoInteractions(encoder);
    }

    private Employee invitedEmployee() throws Exception {
        Employee employee = new Employee("test-employee", "테스트 직원", EmployeeRole.EMPLOYEE);
        employee.invite(hash(OLD_TOKEN), NOW.plus(Duration.ofHours(1)));
        return employee;
    }

    private void assertInvalidOldToken() {
        assertThatThrownBy(() -> service.setPassword(new SetPasswordRequest(OLD_TOKEN, PASSWORD)))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).doesNotContain(OLD_TOKEN, PASSWORD);
                });
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
