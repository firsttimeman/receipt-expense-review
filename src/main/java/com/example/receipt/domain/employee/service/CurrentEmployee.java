package com.example.receipt.domain.employee.service;
import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class CurrentEmployee {
    private final EmployeeRepository employees;
    public Employee require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new InsufficientAuthenticationException("로그인이 필요합니다.");
        return employees.findByLoginId(auth.getName()).filter(e -> e.active() && e.passwordHash() != null)
                .orElseThrow(() -> new InsufficientAuthenticationException("로그인이 필요합니다."));
    }
    public String actor() { return "employee:" + require().id(); }
}
