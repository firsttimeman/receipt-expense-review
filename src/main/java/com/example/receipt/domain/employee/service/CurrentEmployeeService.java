package com.example.receipt.domain.employee.service;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.security.EmployeePrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentEmployeeService {
    private final EmployeeRepository employeeRepository;

    public Employee getCurrentEmployee() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException("로그인이 필요합니다.");
        }

        if (!(authentication.getPrincipal() instanceof EmployeePrincipal principal)) {
            throw new InsufficientAuthenticationException("로그인이 필요합니다.");
        }

        Employee employee = employeeRepository.findByLoginId(authentication.getName())
                .orElseThrow(() -> new InsufficientAuthenticationException("로그인이 필요합니다."));

        // 비활성화된 계정이나 폐기된 세션으로 업무를 처리할 수 없도록 확인합니다.
        if (!principal.isValidFor(employee)) {
            throw new InsufficientAuthenticationException("로그인이 필요합니다.");
        }

        return employee;
    }
}
