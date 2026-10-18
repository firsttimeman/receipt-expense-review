package com.example.receipt.domain.employee.security;

import com.example.receipt.domain.employee.entity.Employee;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 인증 필터가 검증한 직원 정보를 현재 HTTP 요청에서만 공유합니다. */
public final class EmployeeRequestContext {
    private static final String ATTRIBUTE = "employee";

    private EmployeeRequestContext() {
    }

    static void store(HttpServletRequest request, Employee employee) {
        request.setAttribute(ATTRIBUTE, employee);
    }

    public static Employee getEmployee() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof Employee employee) {
            return employee;
        }

        return null;
    }

    static void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }
}
