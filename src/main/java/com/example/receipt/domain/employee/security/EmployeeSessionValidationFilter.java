package com.example.receipt.domain.employee.security;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class EmployeeSessionValidationFilter extends OncePerRequestFilter {
    private final EmployeeRepository employees;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        Employee employee = employees.findByLoginId(authentication.getName()).orElse(null);
        if (!(authentication.getPrincipal() instanceof EmployeePrincipal principal)
                || !principal.isValidFor(employee)) {
            rejectAuthentication(request, response);
            return;
        }

        refreshAuthorities(employee);
        filterChain.doFilter(request, response);
    }

    private void rejectAuthentication(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void refreshAuthorities(Employee employee) {
        // 로그인 이후 역할이 변경되어도 현재 DB의 권한을 사용합니다.
        EmployeePrincipal principal = EmployeePrincipal.forSession(employee);

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
