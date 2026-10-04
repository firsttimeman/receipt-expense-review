package com.example.receipt.domain.employee.security;

import com.example.receipt.domain.employee.entity.Employee;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.List;

/** 로그인 시점의 직원 식별자와 세션 버전을 Redis에 함께 보관합니다. */
public final class EmployeePrincipal extends User {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Long employeeId;

    private final long sessionVersion;

    private EmployeePrincipal(Employee employee, String password) {
        super(employee.loginId(), password, employee.active(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + employee.role().name())));
        this.employeeId = employee.id();
        this.sessionVersion = employee.sessionVersion();
    }

    public static EmployeePrincipal forAuthentication(Employee employee) {
        return new EmployeePrincipal(employee, employee.passwordHash());
    }

    public static EmployeePrincipal forSession(Employee employee) {
        return new EmployeePrincipal(employee, "");
    }

    public boolean isValidFor(Employee employee) {
        return employee != null
                && employeeId.equals(employee.id())
                && sessionVersion == employee.sessionVersion()
                && employee.active()
                && employee.passwordHash() != null;
    }
}
