package com.example.receipt.domain.employee.security;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnWebApplication
public class EmployeeUserDetailsService implements UserDetailsService {
    private final EmployeeRepository employees;

    @Override
    public UserDetails loadUserByUsername(String loginId) {
        Employee employee = employees.findByLoginId(loginId)
                .filter(foundEmployee -> foundEmployee.passwordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("인증할 수 없습니다."));

        // 비밀번호 검증 전에 읽은 버전을 유지해야 로그인 중 비활성화된 계정도 차단할 수 있습니다.
        return EmployeePrincipal.forAuthentication(employee);
    }
}
